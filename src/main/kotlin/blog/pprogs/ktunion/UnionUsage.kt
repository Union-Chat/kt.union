package blog.pprogs.ktunion

import com.andreapivetta.kolor.blue
import com.andreapivetta.kolor.lightYelllow
import com.andreapivetta.kolor.red
import groovy.lang.Binding
import groovy.lang.GroovyShell
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

fun main(args: Array<String>) {
    val things = File("thing.txt").readLines()
    val client = UnionClient(selfbot = true, username = things[0], password = things[1])

    client.onConnect = {
        println("Connected")
    }

    client.onStatusChange = { who, online ->
        println("* $who went ${if (online) "online" else "offline"}".lightYelllow())
    }

    client.onTextMessage = { who, content, id ->
        println(String.format("%-100s %s", "<$who${if (who == client.username) "*" else ""}> $content", id.blue()))
    }

    client.onMessageDelete = { _, content ->
        println("The message \"$content\" was deleted.".red())
    }

//    client.onBeforeMessageSent = {
//
//    }

    client.addCommand(Command("hello", "Says hello")) {
        client.sendMessage("Why are you talking to yourself.")
    }

    client.addCommand(Command("mock", "Mocks u")) {
        client.sendMessage(
                it.args.joinToString(" ").map {
                    if (Math.random() > .5) it.toUpperCase() else it.toLowerCase()
                }.joinToString(""))
    }

    client.addCommand(Command("eval", "Evals some code")) {
        val shell = createShell(it)
        try {
            val result = shell.evaluate(it.args.joinToString(" "))
            if (result == null) {
                client.sendMessage("👌")
            } else {
                client.sendMessage(result.toString())
            }
        } catch (e: Exception) {
            client.sendMessage("Error:\n\n$e")
        }
    }

    client.addCommand(Command("weather", "Send the weather!")) {
        val response = OkHttpClient().newCall(Request.Builder()
                .url("http://wttr.in/?TQn0")
                .header("User-Agent", "curl")
                .build()).execute().body()!!.string()
        it.reply("​\n$response")
    }

    client.start()
}

private fun createShell(ctx: Context): GroovyShell {
    val binding = Binding().apply {
        setVariable("client", ctx.client)
        setVariable("args", ctx.args)
        setVariable("command", ctx.command)
        setVariable("cmd", ctx.command)
        setVariable("sender", ctx.sender)
        setVariable("author", ctx.sender)
        setVariable("server", ctx.server)
        setVariable("socket", ctx.socket)
        setVariable("ctx", ctx)
        setVariable("data", ctx.data)
    }

    return GroovyShell(binding)
}