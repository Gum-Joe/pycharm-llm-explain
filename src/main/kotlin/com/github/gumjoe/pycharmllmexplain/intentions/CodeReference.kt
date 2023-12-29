package com.github.gumjoe.pycharmllmexplain.intentions

enum class CodeReferenceTypes {
    RAW_CODE,
    OPENAI_SUMMARY
}

/** Used to encode how we are provided the details of a code reference to be used in the summary - either as raw code or as a summary of the referene we generate using GPT */
data class CodeReference (
    val type: CodeReferenceTypes,
    val data: String,
    val name: String
)

fun referenceToPrompt(reference: CodeReference): String {
    return when (reference.type) {
        CodeReferenceTypes.RAW_CODE -> "### `${reference.name}`\n```python\n${reference.data}\n```\n"
        CodeReferenceTypes.OPENAI_SUMMARY -> "### `${reference.name}`\nSummary of method: ${reference.data}\n"
    }
}