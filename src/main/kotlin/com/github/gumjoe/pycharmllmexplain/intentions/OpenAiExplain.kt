package com.github.gumjoe.pycharmllmexplain.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.impl.PyFunctionImpl
import com.theokanning.openai.client.OpenAiApi
import com.theokanning.openai.completion.chat.ChatCompletionRequest
import com.theokanning.openai.completion.chat.ChatMessage
import com.theokanning.openai.service.OpenAiService
import com.theokanning.openai.service.OpenAiService.*
import okhttp3.OkHttpClient
import org.jetbrains.annotations.NonNls
import java.time.Duration
import javax.swing.SwingUtilities


// This has 128k context, allowing us to cheat a little bit and use the entire method as context.
const val OPENAI_MODEL = "gpt-4-1106-preview"
const val OPENAI_TEMPERATURE = 0.1

@NonNls
class OpenAiExplain : IntentionAction, PsiElementBaseIntentionAction() {

    private val log: Logger = Logger.getInstance(OpenAiExplain::class.java)

    override fun getText(): String {
        return "Explain code with OpenAI GPT-4"
    }

    override fun getFamilyName(): String {
        return "Documentation"
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    /**
     * Determines if we can explain the given element
     *
     * @param project the current project
     * @param editor the current editor (can be null)
     * @param element the element to check for a parent method
     * @return true if code can be explained, false otherwise
     */
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val parent = PsiTreeUtil.getParentOfType(element, PyFunctionImpl::class.java)
        // Only trigger if method
        if (parent != null) {
            return true
        }
        return false
    }

    /**
     * Generates JavaDoc documentation for a given method using OpenAI's ChatGPT model.
     *
     * @param project the current project
     * @param editor the current editor (can be null)
     * @param element the PsiElement representing the method to generate documentation for
     *
     * The method first grabs the source code of the given method and the parent class's name and documentation comment.
     * It then assembles a prompt using this information and sends it to OpenAI's ChatGPT model to generate documentation.
     * The generated documentation is then validated and added to the method as a JavaDoc comment.
     *
     * If the OpenAI API key is not specified in the environment variable OPENAI_API_KEY, an error dialog is shown.
     * If OpenAI does not return a response or the response is invalid, an error dialog is shown.
     */
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {

        // Setup - grab method (which was already checked) and source code

        val method = PsiTreeUtil.getParentOfType(element, PyFunctionImpl::class.java)!!
        val sourceCode = method.text

        log.warn("Source code: $sourceCode")

        // Assemble prompt
        val prompt = getPrompt(sourceCode)

        log.warn("Prompt: $prompt")

        // Load OpenAI
        val openAiToken = System.getenv("OPENAI_API_KEY")
        if (openAiToken == null) {
            Messages.showErrorDialog(
                "Please specify your OpenAI API key in the environment variable OPENAI_API_KEY",
                "Plugin Error"
            )
            return
        }

        // Code From https://github.com/TheoKanning/openai-java - needed to extend timeout
        val mapper = defaultObjectMapper()
        val client: OkHttpClient = defaultClient(openAiToken, Duration.ofMinutes(2))
            .newBuilder()
            .build()
        val retrofit = defaultRetrofit(client, mapper)

        val api: OpenAiApi = retrofit.create(OpenAiApi::class.java)
        val service = OpenAiService(api)

        // Create request to OpenAI. We use ChatGPT as our responder.
        val completionRequest = ChatCompletionRequest.builder()
            .temperature(OPENAI_TEMPERATURE)
            .model(OPENAI_MODEL)
            .messages(
                listOf(
                    ChatMessage("user", prompt)
                )
            )
            .build()

        // Run our request to OpenAI in a progress manager
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "Requesting explanation...", true) {
                override fun run(progressIndicator: ProgressIndicator) {
                    progressIndicator.isIndeterminate = true
                    progressIndicator.text = "Requesting explanation..."

                    log.debug("Requesting explanation from OpenAI...")
                    // Actually get the documentation
                    var response =
                        service.createChatCompletion(completionRequest).choices[0]?.message?.content?.toString()
                    if (response == null) {
                        SwingUtilities.invokeLater {
                            Messages.showErrorDialog(
                                "OpenAI did not return a response!",
                                "OpenAI Error"
                            )
                        }
                        return
                    }

                    // If response enclosed within backticks, extract it!
                    if ("^\\s*```(.*)```\\s*$".toRegex(RegexOption.DOT_MATCHES_ALL) matches response) {
                        response = response.trim().drop(3).dropLast(3)
                    }

                    // DONE!
                    progressIndicator.fraction = 1.0

                    // Display result in dialog
                    SwingUtilities.invokeLater {
                        Messages.showInfoMessage(
                            "OpenAI response:\n$response",
                            "OpenAI Response"
                        )
                    }
                }
            })


        // Done!


    }

    /**
     * The actual prompt we use for ChatGPT
     */
    private fun getPrompt(code: String) = run {
        """YYou are ExplainPythonGPT, an expert at explaining Python code, even if the code is incomplete. Explain the provided code, noting however to always be honest with the user: if you do not know what part of a method does, say this.

Explanations should be clear and concise and targeted at professional coders who want to quickly know what the code does. Not every line needs explaining in detail, we are interested more in what a method does and how it achieves that. Remember, if the method is extremely long you will need to be succient in your explanation as you only have so much output tokens available!
Python code:
```
$code
```
"""
    }

}