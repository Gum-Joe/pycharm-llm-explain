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
import com.theokanning.openai.completion.chat.ChatCompletionRequest
import com.theokanning.openai.completion.chat.ChatMessage
import org.jetbrains.annotations.NonNls
import javax.swing.SwingUtilities

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
     * Generates a summary of a python method
     *
     * @param project the current project
     * @param editor the current editor (can be null)
     * @param element the PsiElement representing the method to generate documentation for
     *
     * The method first grabs the source code of the given method. It then extracts all references to other methods, which are used as context for the OpenAI GPT-4 model.
     * The method then generates a prompt for the OpenAI GPT-4 model, which is then used to generate a summary of the method.
     *
     * If the combined method references + method body is too long we summarise each reference individually, and then summarise the method body using the summaries of the references as contetx instead of their raw source code
     *
     * If the OpenAI API key is not specified in the environment variable OPENAI_API_KEY, an error dialog is shown.
     * If OpenAI does not return a response or the response is invalid, an error dialog is shown.
     */
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {

        // Setup - grab method (which was already checked) and source code

        val method = PsiTreeUtil.getParentOfType(element, PyFunctionImpl::class.java)!!
        val sourceCode = method.text

        // Load openai - if not available, show error dialog
        val service = getOpenAi()
        if (service == null) {
            SwingUtilities.invokeLater {
                Messages.showErrorDialog(
                    "Failed to get OpenAI Service",
                    "Please specify your OpenAI API key in the environment variable OPENAI_API_KEY to use this plugin"
                )
            }
            return
        }

        // Get ready
        val processor = MethodProcessor(method)
        val preparedMethod = processor.prepareMethod()

        // Run our request to OpenAI in a progress manager
        ProgressManager.getInstance()
            .run(object : Task.Modal(project, "Requesting Explanation...", true) {
                override fun run(progressIndicator: ProgressIndicator) {
                    progressIndicator.isIndeterminate = true
                    progressIndicator.text = "Requesting explanation..."

                    log.debug("Requesting explanation from OpenAI...")
                    // Actually get the summary
                    var response = processor.runOpenAi(preparedMethod)
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

}