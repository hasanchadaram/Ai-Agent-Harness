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
- unsloth
- qwen3_5
- lora
- dictation
- transcript-cleanup
- text-editing
---

# Dictation Cleanup — Qwen3.5-4B GGUF

**Release preparation draft.** This card describes the structure-aware fine-tune from November 1, 2026, run suffix `1793521666`. It does **not** describe the older `1793519004` run selected in the export screenshot. Before publishing, confirm the chosen checkpoint, add the exported filename/size/hash and tested runtime version, and replace this paragraph with the actual release status. No GGUF export or quantized-model evaluation has been verified yet.

This is a specialized English transcript-cleanup model based on [Unsloth's Qwen3.5-4B](https://huggingface.co/unsloth/Qwen3.5-4B), derived from [Qwen/Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B). It is designed to turn an already transcribed dictation into readable text, without answering the questions or executing the requests inside it.

It takes **text**, not microphone audio. Speech recognition must happen before this model. This release does not claim validated image or audio capabilities.

## Intended behavior

- Remove speech fillers, accidental repetitions and false starts.
- Correct clear grammar, spelling and punctuation errors while preserving voice and meaning.
- Resolve spoken self-corrections and contextual punctuation commands.
- Normalize unambiguous numbers, currency, dates and times.
- Format supplied content into bullets, numbered procedures, paragraph breaks or email layout when appropriate.
- Preserve dictated questions and requests as text; do not answer them or generate missing content.
- Return only the cleaned transcript. Filler-only speech targets an empty completion.

## Prompting

Send one raw transcript as a user message in a fresh conversation. The fine-tuning data has **no system message or cleanup instruction**. Retain the model-native chat template and end-of-turn handling. Keep thinking, tools, Web Search, tool nudging and agentic retries disabled.

```json
{
  "messages": [
    {"role": "user", "content": "um can you send the report by thursday no wait friday period"}
  ]
}
```

Illustrative target:

```text
Can you send the report by Friday?
```

A question such as `what's the capital of france` should produce `What's the capital of France?`, rather than the answer. Local formatting instructions may change the layout of supplied content. A request to invent a list or compose an undictated email should remain a cleaned request.

These are behavior examples, not a published measured score. Raw completion mode without the chat template is not the intended interface.

## GGUF files

Planned first quantization: **Q4_K_M**. Optional larger variant: **Q5_K_M**. Export the **full merged model** so consumers do not need a separate base-model download or LoRA adapter.

Before publishing, replace this section with a file table containing the real filenames, quantization types, byte sizes and download links. Add SHA-256 checksums in `SHA256SUMS`. The export screen's size estimates are not measured artifact sizes.

Q4_K_M changes weight precision; it does not reduce the parameter count to less than 4B or itself shorten the context window. Runtime memory also includes context caches and other overhead beyond the GGUF file size.

## Running locally

Use an up-to-date llama.cpp-compatible runtime with Qwen3.5 support. Test the actual runtime before claiming compatibility. The model is intended for text-only cleanup; a vision projector is not required for text-only use. Do not advertise image support unless the appropriate projector is shipped and tested.

After downloading the release file, a local llama.cpp server can be started along these lines, substituting the real filename:

```text
llama-server -m ./dictation-cleanup-qwen3.5-4b.Q4_K_M.gguf -c 4096 --host 127.0.0.1 --port 8080
```

Then use its chat-completions interface with a user-only `messages` array and no `tools`. Keep the Qwen3.5-4B non-thinking setting. Confirm the template embedded in the GGUF is the one saved with the fine-tune. A 4096-token serving window is a starting setting matching this run's configured training sequence length, not evidence of tested 4096-token dictation quality.

Record the exact tested runtime build, generation settings, hardware and context length here before release. No measured throughput or minimum RAM/VRAM is claimed in this draft. The architecture's native context capability is not evidence that the cleanup fine-tune has been validated on long inputs.

## Training data

The model was trained on 2,478 English synthetic transcript/cleanup pairs in user/assistant conversation format. The accompanying corpus has 320 validation and 320 test pairs; they were not configured as evaluation inputs during this run.

The initial corpus consists of 98 manually specified template families and 60 boundary fixtures, expanded with names, places and objects. The structure-aware revision adds 118 individually specified examples across all splits, of which 78 enter training. There are 3,118 total examples across train, validation and test, **not 3,118 training examples**.

Coverage includes dictated questions and commands, fillers versus meaningful uses of the same words, corrections, numbers, technical tokens, formatting and ordinary prose that should remain unformatted. The labels are AI-authored and have not been independently human-validated. No user recordings were used to create this corpus.

Dataset availability: not uploaded as part of this draft. If the data is released separately, add its Hub dataset identifier, license, version and dataset card here. Do not add a nonexistent dataset to the YAML metadata.

## Training procedure

Recorded settings for run `1793521666`:

| Setting | Value |
|---|---|
| Base model | `unsloth/Qwen3.5-4B` |
| Method | LoRA, base not loaded in 4-bit |
| LoRA rank / alpha | 16 / 16 |
| LoRA dropout | 0.0 |
| Epochs | 2 |
| Learning rate | 0.0002 |
| Per-device batch size | 2 |
| Gradient accumulation | 4 |
| Configured sequence length | 4096 |
| Packing | Disabled |
| Train on completions only | Disabled |
| Final reported step | 620 |
| Final reported training loss | 0.2121936977 |
| In-training evaluation | Not configured |

The final reported training loss is not accuracy and is not a held-out evaluation score. Confirm these settings against the exported checkpoint if selecting a different run or an intermediate checkpoint. Record the export precision, converter/quantizer versions and whether an importance matrix was used after conversion.

## Evaluation and limitations

The author reports satisfactory results on personally tested examples of the fine-tuned model. No aggregate held-out score, independently reproduced benchmark, or quantized-model equivalence result has been established for this draft.

Before release, compare the exported GGUF against the chosen unquantized checkpoint on unseen speech transcripts: questions and commands must remain dictated content, names/numbers/negation must survive, corrections must be scoped correctly, and structure should be added only when appropriate. Record results and generation settings without using training examples as evidence of generalization.

Known limits include synthetic template concentration, limited real-ASR noise coverage, short training examples, sparse empty-output examples, and lack of task-specific multilingual or code-switching evaluation. The model can omit words, alter meaning, over-format, or answer instead of clean; applications should allow review of the original transcript. It is not a factual, medical or legal assistant.

In Unsloth comparisons, enabled tools and automatic tool nudging can inject hidden follow-up messages that this model then cleans as text. This can appear as unrelated appended instructions. Turn those features off for cleanup inference; do not treat that interaction as a reason to remove legitimate dictated wording from outputs.

## License and provenance

The upstream Qwen3.5-4B and Unsloth base-model cards identify Apache-2.0. This draft uses the same proposed license for the release. Include the upstream license text and any required attribution/NOTICE material in the release, clearly identify that this is a modified fine-tuned and quantized derivative, and link the original model authors.

Keep the original adapter and tokenizer/template as a source artifact for future training. GGUF is the distribution format for this inference release; it is not the preferred source for continuing fine-tuning.
