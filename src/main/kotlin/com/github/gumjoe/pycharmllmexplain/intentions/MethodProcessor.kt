package com.github.gumjoe.pycharmllmexplain.intentions

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.suggested.startOffset
import com.jetbrains.python.psi.impl.PyCallExpressionImpl
import com.jetbrains.python.psi.impl.PyFunctionImpl
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import com.theokanning.openai.completion.chat.ChatCompletionRequest
import com.theokanning.openai.completion.chat.ChatMessage


private val registry: EncodingRegistry = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
private val enc: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)
// We use gpt 4 turbo, which has 128k context
private const val MAX_CONTEXT_TOKENS = 100000

private const val OPENAI_SHORT_CHEAP_EXPLAINER_MODEL = "gpt-3.5-turbo"
private const val OPENAI_SUMMARY_MODEL = "gpt-4-1106-preview"
private const val OPENAI_TEMPERATURE = 0.1

class MethodProcessor(private val method: PyFunctionImpl) {

    private val log: Logger = Logger.getInstance(MethodProcessor::class.java)

    data class PreparedMethod(val name: String, val body: String, val references: Map<String, String>)

    /**
     * Returns a map of method references to their code
     */
    fun prepareMethod(): PreparedMethod {
        // 1: Extract method body
        val rootSourceCode = method.text
        // 2: Extract list of method refs inside method body
        val childMap = HashMap<String, PsiElement>()
        val children = PsiTreeUtil.findChildrenOfType(method, PyCallExpressionImpl::class.java)
        for (child in children) {
            val ref = child.callee?.reference?.resolve()
            if (ref != null) {
                val key = "${child.callee?.containingFile?.name}#${child.callee?.name}"
                log.debug("(${child.callee?.containingFile?.name}#${child.callee?.name}) - Offset: ${child.startOffset}, Offset in parent: ${child.startOffset - method.startOffset}")
                //log.warn(ref.text)
                if (childMap.containsKey(key)) {
                    log.warn("Duplicate key: $key")
                } else {
                    childMap[key] = ref
                }
            }
        }

        log.debug("============== DONE with ${method.name} ============")

        return PreparedMethod(method.name ?: "UNKNOWN", rootSourceCode, childMap.mapValues { (name, reference) -> reference.text })
    }

    public fun runOpenAi(method: PreparedMethod): String {
        // Step off point:
        // Conact method body and check length
        val minimumContent = StringBuilder()
        minimumContent.append(method.body)
        for (childBody in method.references.values) {
            minimumContent.append(childBody)
        }
        val tokens = enc.encode(minimumContent.toString())

        if (tokens.size > 0) {
            log.warn("Too many tokens!: ${tokens.size}")
        } else {
            log.debug("Tokens: ${tokens.size}")
        }

        // If too long, summarise the referenced methods and use that as context instead
        val referencesForSummary = if (tokens.size > MAX_CONTEXT_TOKENS) method.references.mapValues { (name, referenceBody) ->
            CodeReference(
                CodeReferenceTypes.OPENAI_SUMMARY, explainCode(
                    name,
                    referenceBody
                ), name
            )
        } else method.references.mapValues { (name, referenceBody) ->
            CodeReference(
                CodeReferenceTypes.RAW_CODE,
                referenceBody,
                name
            )
        }

        // NOTE: A future improvement may be to split the code up into chunks and summarise each chunk individually, then combine the summaries into a single summary, if we still have too much context

        // We can now create a summary of the method
        // 3: Generate summary of method body
        val summary = generateSummary(method.name, method.body, referencesForSummary)

        // Return it
        log.debug("Summary: $summary")
        return summary
    }

    private fun generateSummary(name: String?, rootSourceCode: String, referencesForSummary: Map<String, CodeReference>): String {
        val prompt = getSummaryPrompt(name ?: "UNKNOWN", rootSourceCode, referencesForSummary)
        log.debug("Prompt: $prompt")
        val completionRequest = ChatCompletionRequest.builder()
            .temperature(OPENAI_TEMPERATURE)
            .model(OPENAI_SUMMARY_MODEL)
            .messages(
                listOf(
                    ChatMessage("user", prompt)
                )
            )
            .build()
        log.debug("Requesting summary from OpenAI...")
        val service = getOpenAi()
        if (service == null) {
            log.warn("OpenAI service is null!")
            throw Exception("OpenAI service is null! Cannot proceed with processing.")
        }
        // Actually get the documentation
        val response =
            service.createChatCompletion(completionRequest).choices[0]?.message?.content?.toString()
        if (response == null) {
            log.warn("OpenAI response is null!")
            throw Exception("OpenAI response is null! Cannot proceed with processing.")
        }

        return response
    }

    private fun getSummaryPrompt(name: String, rootSourceCode: String, referencesForSummary: Map<String, CodeReference>): String? {
        val promptBuilder = StringBuilder()
        promptBuilder.append("You are ExplainPythonGPT, an expert at explaining Python code, even if the code is incomplete.\n")
        promptBuilder.append("You will first be provided with the method the code to explain references (either as code or a summary of the code), then the code itself to explain.\n")
        promptBuilder.append("\n")
        promptBuilder.append("Explain ONLY the code we ask to explain (denoted as \"Code to Explain:\"), noting however to always be honest with the user: if you do not know what part of a method does, say this.\n")
        promptBuilder.append("Explanations should be clear and concise and targeted at professional coders who want to quickly know what the code does.\n")
        promptBuilder.append("Not every line needs explaining in detail, we are interested more in what a method does and how it achieves that.\n")
        promptBuilder.append("Remember, if the method is extremely long you will need to be succient in your explanation as you only have so much output tokens available!\n")
        promptBuilder.append("\n")
        promptBuilder.append("## Method Metadata:\n")
        promptBuilder.append("Method Name: $name\n")
        promptBuilder.append("Number of references: ${referencesForSummary.size}\n")

        promptBuilder.append("## References\n")
        promptBuilder.append("\n")

        // Add references
        for (reference in referencesForSummary.values) {
            promptBuilder.append(referenceToPrompt(reference))
        }

        promptBuilder.append("\n## Code to Explain:\n")
        promptBuilder.append("```python\n")
        promptBuilder.append(rootSourceCode)
        promptBuilder.append("\n```\n")

        promptBuilder.append("Please provide the explanation of ONLY the Code to Explain.\n")
        return promptBuilder.toString()
    }

    private fun explainCode(name: String, sourceCode: String): String {
        log.debug("Explain: $name with GPT 3.5")
        val prompt = getExplainerPrompt(name, sourceCode)
        log.debug("Prompt: $prompt")
        val completionRequest = ChatCompletionRequest.builder()
            .temperature(OPENAI_TEMPERATURE)
            .model(OPENAI_SHORT_CHEAP_EXPLAINER_MODEL)
            .messages(
                listOf(
                    ChatMessage("user", prompt)
                )
            )
            .build()
        log.debug("Requesting explanation from OpenAI...")
        val service = getOpenAi()
        if (service == null) {
            log.warn("OpenAI service is null!")
            throw Exception("OpenAI service is null! Cannot proceed with processing.")
        }
        // Actually get the documentation
        val response =
            service.createChatCompletion(completionRequest).choices[0]?.message?.content?.toString()
        if (response == null) {
            log.warn("OpenAI response is null!")
            throw Exception("OpenAI response is null! Cannot proceed with processing.")
        }

        return response
    }

    private fun getExplainerPrompt(name: String, sourceCode: String): String {
        val prompt = StringBuilder()
        prompt.append("You are SummaryExplainGPT, an excellent code summarizer of Python code. Output ONLY short, 1-3 line summary of the code provided. It is important you keep the description short and succinct, i.e. a summary.\n")
        prompt.append("#### Name:\n")
        prompt.append(name + "\n")
        prompt.append("#### Source code:\n")
        prompt.append("```python\n")
        prompt.append(sourceCode)
        prompt.append("\n```\n")

        return prompt.toString()
    }
}