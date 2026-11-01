---
language:
- en
license: apache-2.0
license_link: https://huggingface.co/Qwen/Qwen3.5-4B/blob/main/LICENSE
base_model: unsloth/Qwen3.5-4B
base_model_relation: finetune
library_name: gguf
pipeline_tag: text-generation
tags:
- gguf
- llama.cpp
- llama-cpp
- unsloth
- qwen3_5
- lora
- dictation
- transcript-cleanup
- text-editing
- vision-language-model
---

# Dictation Cleanup — Qwen3.5-4B GGUF

This is a fine-tuned, quantized model for **cleaning English dictated transcripts**. It takes text already produced by a speech-to-text system and returns a cleaned transcript. It is designed to remove fillers, repair punctuation and obvious grammar, handle spoken corrections, and add structure when the dictated content calls for it.

The model treats questions, instructions and requests as words to be written down. For example, it should clean “what's the capital of france” to “What's the capital of France?” rather than answer the question. Local formatting requests can affect the layout of content already supplied, but the model is not intended to generate missing content or execute commands.

This is a text cleanup model, **not a speech recognition model**. Audio must first be transcribed separately. Its base is [unsloth/Qwen3.5-4B](https://huggingface.co/unsloth/Qwen3.5-4B), derived from [Qwen/Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B). The LoRA adapter was merged into the base and exported to GGUF using [Unsloth](https://github.com/unslothai/unsloth).

## Files

| File | Purpose | Size | SHA-256 |
|---|---|---:|---|
| [Qwen3.5-4B.Q4_K_M.gguf](https://huggingface.co/hasanchadaram/dictation-cleanup-qwen3.5-4b-gguf/resolve/main/Qwen3.5-4B.Q4_K_M.gguf) | Merged text model, Q4_K_M quantization | 2,783,446,880 bytes (~2.59 GiB) | `d909820f5e6e42b5f3623763f70babdc93ee33aa8f6677ad3ea182f05f3f40dd` |
| [Qwen3.5-4B.BF16-mmproj.gguf](https://huggingface.co/hasanchadaram/dictation-cleanup-qwen3.5-4b-gguf/resolve/main/Qwen3.5-4B.BF16-mmproj.gguf) | Optional vision projector exported with the model | 675,568,864 bytes (~0.63 GiB) | `01b077c9411a508a38aa16969355a5f50ef53d6d934a34137d12dd60e24a1688` |

The projector is **not needed for text-only cleanup**. Although the base architecture and exported projector support multimodal workflows, image capabilities have not been evaluated for this fine-tune. Quantization changes weight precision; the model still has approximately 4 billion parameters. Runtime memory depends on the runtime, context length, caches, and offloading, and will generally exceed the model file size.

## Intended use

Send one raw transcript as a user message and use the assistant response as the cleaned transcript. Examples of intended transformations include:

- Remove hesitation words, false starts, and accidental repetition when they carry no meaning.
- Correct clear spelling, grammar, capitalization, and punctuation errors without changing the speaker's voice or intent.
- Apply clear self-corrections and convert spoken punctuation when it is being used as a formatting command.
- Normalize unambiguous dates, times, quantities, and currency.
- Use bullet points, numbered procedures, line breaks, paragraphs, and email layout when the supplied text supports them; avoid over-formatting short prose.
- Preserve names, technical identifiers, quantities, negation, and uncertainty.

For example:

```text
Input:  um can you send the report by thursday no wait friday period
Target: Can you send the report by Friday?
```

This is an illustrative target, not a measured model output. The intended output for empty or filler-only input is empty. The model may not consistently satisfy every rule; applications should retain the original transcript and allow the user to review edits.

## Run locally

Use a current `llama.cpp`-compatible runtime that supports Qwen3.5. Download the Q4_K_M file and start a server with, for example:

```bash
llama-server \
  --model Qwen3.5-4B.Q4_K_M.gguf \
  --ctx-size 4096 \
  --host 127.0.0.1 \
  --port 8080
```

Send a chat-completions request with the transcript as the **user** content:

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "um can you send the report by thursday no wait friday period"}
    ],
    "temperature": 0.2,
    "max_tokens": 256
  }'
```

The fine-tuning examples used a user/assistant conversation with **no system message or cleanup instruction**. Use the model's chat template and stop-token handling, a fresh conversation for each transcript, and non-thinking mode. Disable tools and agentic retries or tool-call nudging: an inserted hidden follow-up can itself be treated as text to clean. Adjust `max_tokens` for longer outputs; the example values are starting points, not benchmarked optimal settings.

The configured training sequence length was 4096 tokens. This does not establish reliable cleanup across that entire length, nor does it establish a minimum hardware requirement or tested compatibility with a particular runtime version. A compatible runtime must recognize the Qwen3.5 architecture and the model's embedded chat template.

## Training data

Training used **2,478 English synthetic input/output pairs**, represented as raw transcript user messages and cleaned assistant messages. The larger corpus contains 320 validation and 320 test examples in addition to the training split. The validation and test splits were not configured for in-training evaluation in this run.

The original 3,000-example corpus was generated from 98 manually specified template families and 60 boundary fixtures, with substitutions such as names and places. A structure-aware revision added 118 individually specified examples across all splits, 78 of them in training. Consequently, the number of independent linguistic patterns is much smaller than the row count. Labels are AI-authored and have not been independently human-validated. No user recordings were used to create this training corpus.

The examples cover questions and requests, fillers, repetition, self-corrections, spoken punctuation, numbers, technical terms, lists, email layout, topic breaks, and ordinary prose. The data is synthetic and is not currently linked to a public dataset repository in this model card.

## Training and conversion

| Setting | Value |
|---|---|
| Base model | `unsloth/Qwen3.5-4B` |
| Training method | LoRA; base model not loaded in 4-bit for training |
| LoRA rank / alpha | 16 / 16 |
| LoRA dropout | 0.0 |
| Epochs | 2 |
| Learning rate | 0.0002 |
| Per-device batch size | 2 |
| Gradient accumulation steps | 4 |
| Configured sequence length | 4096 |
| Sequence packing | Disabled |
| Train on completions only | Disabled |
| Final training step | 620 |
| Final reported training loss | 0.2122 |
| In-training evaluation | Not configured |
| Release conversion | LoRA merged into 16-bit weights, then exported as Q4_K_M GGUF |

The reported loss is a training metric, **not accuracy or a held-out evaluation result**. The exported checkpoint was the completed structure-aware fine-tuning run. The importance-matrix setting and exact converter build were not independently recorded in this card.

## Evaluation and limitations

The author reported satisfactory results on personally tried examples of the fine-tuned model. No aggregate held-out evaluation score, independently reproduced benchmark, or comparison measuring quantization impact is reported. Illustrative examples in this card describe intended behavior; they are not a guarantee of the output.

Important limitations include:

- Training data is predominantly templated synthetic English; real speech-recognition errors, accents, dialects, multilingual dictation, and code-switching have not been systematically evaluated.
- Most training transcripts are short. Performance on long recordings or multi-topic documents is unknown.
- Empty-output and unchanged-text cases are sparsely represented.
- The model can omit or insert words, alter names or numbers, change negation, format unnecessarily, or answer a dictated question rather than transcribe it.
- Recognition errors that remove meaning may not be recoverable from text alone. Do not rely on invented corrections to restore unclear audio.
- The model is not intended as a general factual, medical, or legal assistant, or to independently act on dictated commands.

For release or downstream evaluation, compare the exported GGUF with its source checkpoint on unseen transcripts. Check meaning preservation, names and numbers, negation, self-corrections, formatting decisions, unwanted answers, and filler-only input. Human review is useful because more than one punctuation or formatting choice can be valid.

## License and attribution

The upstream [Qwen3.5-4B model](https://huggingface.co/Qwen/Qwen3.5-4B) and [Unsloth base model](https://huggingface.co/unsloth/Qwen3.5-4B) identify the Apache License 2.0. This repository is a modified fine-tuned and quantized derivative. See the [upstream license](https://huggingface.co/Qwen/Qwen3.5-4B/blob/main/LICENSE) for its terms and attribution requirements.
