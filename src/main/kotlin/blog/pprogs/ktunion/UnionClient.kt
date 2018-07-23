package blog.pprogs.ktunion

import com.beust.klaxon.*
import org.http4k.client.ApacheClient
import org.http4k.client.WebsocketClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Uri
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsStatus
import java.util.*
import kotlin.concurrent.thread

fun String.encode(): String {
    return Base64.getEncoder().encodeToString(this.toByteArray(charset("UTF-8")))
}

class Message(val op: Int, val d: Any)

class TextMessage(val content: String)

class Command(val name: String, val help: String)

class Member(val id: String, val online: Boolean, val avatarUrl: String = "http://union.serux.pro/default_avatar.png")

class Server(val id: Int, val members: List<Member>, val iconUrl: String, val name: String, val owner: String)

class Context(val server: Int, val socket: Websocket, val command: Command, val args: List<String>, val sender: String, val client: UnionClient, val data: Message) {
    fun reply(message: String) {
        client.sendMessage(message, server)
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class UnionClient(val selfbot: Boolean = false, val username: String, val password: String, val silent: Boolean = false, val mock: Boolean = false, val bot: Boolean = true) {
    var servers = mutableMapOf<Int, Server>()
    var messages = mutableMapOf<String, String>()

    private var authString = "Basic ${"$username:$password".encode()}"

    val client = ApacheClient()
    var socket = WebsocketClient.nonBlocking(
            Uri.of("wss://union.serux.pro:2096"),
            listOf(Pair("Authorization", authString)),
            onConnect = {
                onOpen()
            }
    )
    val commands = mutableMapOf<Command, (Context) -> Unit>()
    val clientSideCommands = mutableMapOf<Command, (List<String>) -> Unit>()

    var onConnect: () -> Unit = { println("Connected") }
    var onError: (Throwable?) -> Unit = { t -> println("Error:"); t?.printStackTrace() }
    var onStartClosing: (Int, String?) -> Unit = { code, reason ->
        //        socket.close(code, reason)
        println("Closing. Code: $code, reason: $reason")
    }
    var onRawWSMessage: (String) -> Unit = { _ -> }
    var onJsonWSMessage: (Message) -> Unit = { _ -> }
    var onStartClosed: (Int, String?) -> Unit = { _, _ -> }
    var onTextMessage: (Member, String, String) -> Unit = { _, _, _ -> }
    val onCommand: (Context) -> Boolean = { _ -> true }
    var onStatusChange: (String, Boolean) -> Unit = { _, _ -> }
    var onMessageDelete: (String, String) -> Unit = { _, _ -> }
    var onBeforeMessageSent: (String) -> String = { it }

    fun addCommand(command: Command, callback: (Context) -> Unit) {
        commands[command] = callback
    }

    fun addClientSideCommand(command: Command, callback: (String) -> String) {
//        clientSideCommands[command] = callback
    }

    //    @JvmStatic
    fun start() {

        socket.onMessage {
            onMessage(it.bodyString())
        }

        socket.onError(::onFailure)

        socket.onClose { onClosed(it) }

        if (mock) {
            thread {
                while (true) {
                    val options = listOf("wow a message", "this is a long message " * 30)
                    onTextMessage(Member("Not a person", true, "yes"), options.random(), "id")
                    Thread.sleep(2000)
                }
            }
        } else {
            commands.apply {

                put(Command("ping", "A simple ping command")) { ctx ->
                    ctx.reply("ur face is a pong!1!11!")
                }

                put(Command("help", "What you're reading")) { ctx ->
                    if (ctx.args.size == 1) {
                        val result = commands.keys.firstOrNull { it.name == ctx.args.first() }
                        if (result == null) {
                            sendMessage("No results for that command found.")
                        } else {
                            sendMessage("Help description for $: ${result.help}")
                        }
                    }
                    var final = "Here are my commands:\n\n"
                    commands.keys.forEach { final += String.format("%-20s %s%n", it.name, it.help) }
                    sendMessage(final)
                }

            }
        }
    }

    fun send(data: Map<String, Any>, op: Int = 8) {
        socket.send(WsMessage(Klaxon().toJsonString(Message(op, JsonObject(data)))))
    }

    fun onOpen() {
        onConnect()
        if (!silent) {
            thread {
                while (true) {
                    sendMessage(onBeforeMessageSent(readLine()!!))
                }
            }
        }
    }

    fun sendMessage(text: String, server: Int = 1): Response? {
//        if (!silent) send(mapOf("server" to server, "content" to text))
        if (!silent) {
//            val req = Request.Builder()
//                    .url("https://union.serux.pro/api/server/$server/messages")
//                    .post(
//                            RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
//                                    Klaxon().toJsonString(TextMessage(text)))
//                    )
//                    .header("Authorization", authString)
//                    .build()

            val client1 = Request(Method.POST, "https://union.serux.pro/api/server/$server/messages")
                    .header("Content-Type", "application/json")
                    .body(Klaxon().toJsonString(TextMessage(text)))
                    .header("Authorization", authString)
                    .use(client)

            return client1
        }
        return null
    }

    fun onFailure(error: Throwable) {
        onError(error)
    }

    fun onClosed(status: WsStatus) {
        onStartClosing(status.code, status.description)
    }

    fun onMessage(text: String?) {
        onRawWSMessage(text!!)

        val jsonConverter = object : Converter {
            override fun canConvert(cls: Class<*>): Boolean = cls == Message::class.java

            override fun fromJson(jv: JsonValue): Any {
                return Message(jv.objInt("op"), jv.obj!!["d"]!!)
            }

            override fun toJson(value: Any): String {
                return """{d: ${(value as Message).d}, op: ${value.op}"""
            }
        }

        val data = Klaxon()
                .converter(jsonConverter)
                .parse<Message>(text)!!

        onJsonWSMessage(data)

        @Suppress("UNCHECKED_CAST") // we know it'll be ok
        when (data.op) {
            1 -> {
                Klaxon().parseFromJsonArray<Server>(data.d as JsonArray<JsonObject>)!!.forEach {
                    servers[it.id] = it
                }
            }
            3 -> handleMessage((data.d as JsonObject).string("author")!!, data.d.string("content")!!, data.d.int("server")!!, data, data.d.string("id")!!)
            4 -> onStatusChange((data.d as JsonObject).string("id")!!, data.d.boolean("status")!!)
            6 -> onMessageDelete(data.d as String, messages[data.d]!!)
            else -> println("Message: $text")
        }

    }

    private fun handleMessage(who: String, content: String, server: Int, data: Message, id: String) {
        onTextMessage(servers[server]!!.members.find { it.id == who }!!, content, id)
        messages[id] = content

        if (!bot || !content.startsWith(">")) {
            return
        }


        val commandString = content.substring(1).split(" ")[0].toLowerCase()
        val args = content.split(' ').drop(1)

        val command = commands.filterKeys({ it.name == commandString })

        if (command.isEmpty()) return

        if (selfbot && who != username) {
            sendMessage("It's called a selfboat for a reason, heck off.")
            return
        }

        val context = Context(
                server,
                socket,
                command.keys.first(),
                args,
                who,
                this,
                data
        )

        if (!onCommand(context)) return

        command.values.first()(context)
    }

}

fun <E> List<E>.random(): E {
    return get(Random().nextInt(size))
}

private operator fun String.times(i: Int): String {
    return this.repeat(i)
}
