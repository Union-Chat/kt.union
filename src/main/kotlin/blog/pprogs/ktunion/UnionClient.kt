package blog.pprogs.ktunion

import com.beust.klaxon.*
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun String.encode(): String {
    return Base64.getEncoder().encodeToString(this.toByteArray(charset("UTF-8")))
}

class Message(val op: Int, val d: Any)

class TextMessage(val content: String, val server: Int = 1)

class Command(val name: String, val help: String)

class Context(val server: Int, val socket: WebSocket, val command: Command, val args: List<String>, val sender: String, val client: UnionClient, val data: Message) {
    fun reply(message: String) {
        client.sendMessage(message, server)
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class UnionClient(val selfbot: Boolean = false, val username: String, val password: String, val silent: Boolean = false, val mock: Boolean = false, val bot: Boolean = true) : WebSocketListener() {
    var servers = mutableListOf<Int>()
    var socket: WebSocket? = null
    var messages = mutableMapOf<String, String>()

    private var authString = "Basic ${"$username:$password".encode()}"

    lateinit var client: OkHttpClient

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
    var onTextMessage: (String, String, String) -> Unit = { _, _, _ -> }
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
        if (mock) {
            thread {
                while (true) {
                    val options = listOf("wow a message", "this is a long message " * 30)
                    onTextMessage("not a person", options.random(), "id")
                    Thread.sleep(2000)
                }
            }
        } else {
            client = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val request = Request.Builder()
                    .url("wss://union.serux.pro:2096")
                    .header("Authorization", authString)
                    .build()

            client.newWebSocket(request, this)

            // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
            client.dispatcher().executorService().shutdown()

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
        socket!!.send(Klaxon().toJsonString(Message(op, JsonObject(data))))
    }

    override fun onOpen(webSocket: WebSocket?, response: Response?) {
        socket = webSocket
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
            val req = Request.Builder()
                    .url("https://union.serux.pro/api/message")
                    .post(
                            RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
                                    Klaxon().toJsonString(TextMessage(text, server)))
                    )
                    .header("Authorization", authString)
                    .build()

            return client.newCall(req).execute()
        }
        return null
    }

    override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
        onError(t)
    }

    override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
        webSocket?.close(code, reason)
        onStartClosing(code, reason)
    }

    override fun onMessage(webSocket: WebSocket?, text: String?) {
        onRawWSMessage(text!!)

        val jsonConverter = object: Converter {
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
            1 -> (data.d as JsonArray<JsonObject>).forEach { servers.add(it.int("id")!!) }
            3 -> handleMessage((data.d as JsonObject).string("author")!!, data.d.string("content")!!, data.d.int("server")!!, data, data.d.string("id")!!)
            4 -> onStatusChange((data.d as JsonObject).string("id")!!, data.d.boolean("status")!!)
            6 -> onMessageDelete(data.d as String, messages[data.d]!!)
            else -> println("Message: $text")
        }

    }

    private fun handleMessage(who: String, content: String, server: Int, data: Message, id: String) {
        onTextMessage(who, content, id)
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
                socket!!,
                command.keys.first(),
                args,
                who,
                this,
                data
        )

        if (!onCommand(context)) return

        command.values.first()(context)
    }


    override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
        onStartClosed(code, reason)
    }


}

fun <E> List<E>.random(): E {
    return get(Random().nextInt(size))
}

private operator fun String.times(i: Int): String {
    return this.repeat(i)
}
