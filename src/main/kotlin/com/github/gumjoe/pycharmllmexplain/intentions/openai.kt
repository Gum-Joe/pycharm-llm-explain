package com.github.gumjoe.pycharmllmexplain.intentions

import com.intellij.openapi.ui.Messages
import com.theokanning.openai.client.OpenAiApi
import com.theokanning.openai.service.OpenAiService
import okhttp3.OkHttpClient
import java.time.Duration

fun getOpenAi(): OpenAiService? {
    // Load OpenAI
    val openAiToken = System.getenv("OPENAI_API_KEY")
    if (openAiToken == null) {
        Messages.showErrorDialog(
            "Please specify your OpenAI API key in the environment variable OPENAI_API_KEY",
            "Plugin Error"
        )
        return null
    }

    // Code From https://github.com/TheoKanning/openai-java - needed to extend timeout
    val mapper = OpenAiService.defaultObjectMapper()
    val client: OkHttpClient = OpenAiService.defaultClient(openAiToken, Duration.ofMinutes(2))
        .newBuilder()
        .build()
    val retrofit = OpenAiService.defaultRetrofit(client, mapper)

    val api: OpenAiApi = retrofit.create(OpenAiApi::class.java)
    val service = OpenAiService(api)

    return service
}