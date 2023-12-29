# pycharm-llm-explain
This plugin uses the OpenAI API's GPT-4 turbo model to generate explanations for Python code in PyCharm Community Edition.

## Installation
(assuming you already cloned the repo)

### Sandboxed PyCharm
1. Open this repo in IntelliJ IDEA
2. Run the Gradle task "Run Plugin". This will open a sandboxes, fresh PyCharm instance with the plugin installed

## Running
1. Set the environment variable OPENAI_API_KEY to your OpenAI API Key
2. Select anywhere inside the method you wish to explain
3. Right click, then click "Show Context Actions", followed by "Explain code with OpenAI GPT-4"
4. Wait, and the explanation will appear in a popup!

