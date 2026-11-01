# Unsloth Desktop: no system prompt experiment

These exports preserve the existing examples and splits, but remove the repeated task instruction from the actual model input. Each JSONL line contains one conversation:

```json
{"messages":[{"role":"user","content":"what's the capital of france"},{"role":"assistant","content":"What's the capital of France?"}]}
```

This is an example of the schema. No task prefix, transcript tags, or system message is included. There are 2,400 rows in [train.jsonl](./train.jsonl), 300 in [validation.jsonl](./validation.jsonl), and 300 in [test.jsonl](./test.jsonl). They retain the synthetic provenance and limitations described in the [master README](../README.md). No model has been trained or tested by this export.

## On the screen provided

1. Under **Upload a local file**, upload **train.jsonl** from this directory.
2. Select that uploaded dataset if it is not selected automatically.
3. Use its default subset, if there is one; a plain local JSONL upload may have no subset.
4. Choose **train** as **Train Split**, or the sole split shown by the uploader.
5. In dataset Advanced, select **auto** or **chatml** format. If asked for a conversation column, select **messages**. Confirm that preview shows raw speech as user content and cleaned speech as assistant content, with no task instruction or system message added.
6. The screenshot has one dataset picker, not independent training/evaluation file pickers. A plain JSONL is a single split; **do not select the same entire split for both training and evaluation**. If only that split is available, leave **Evaluation Split = None** and keep the validation and test files for separate generation-based evaluation. Merely uploading the validation file as another dataset does not attach it as an evaluation split.
7. If your installed version exposes a separate evaluation file/path setting, point it to **validation.jsonl**. Otherwise, the route to in-training eval on the exact existing split is a dataset source with named `train` and `validation` splits, or a code-based trainer loading the two local files with an explicit data-files mapping. Do not concatenate the files and assume the UI will discover splits. No dataset has been uploaded to an external service here.

Example explicit loading for a code-based trainer, if you choose that route:

```python
from datasets import load_dataset
dataset = load_dataset("json", data_files={
    "train": "train.jsonl",
    "validation": "validation.jsonl",
})
# After applying the model's chat template:
# train_dataset=dataset["train"], eval_dataset=dataset["validation"]
```

Do not upload the master file, review sample, metadata, or reports as additional training data. They would duplicate examples or train on the wrong content. Keep the test set separate from training and checkpoint selection.

## Qwen3.5-4B starting configuration

These are starting choices, not measured optimal settings:

- **Train as:** Language model (the input is already transcribed text).
- **Method:** Prefer **LoRA with bf16** if your GPU supports it and it fits. Unsloth estimates about 10 GB VRAM for 4B bf16 LoRA, with actual requirements dependent on settings, and specifically advises against Qwen3.5 QLoRA due to quantization differences. The screenshot currently selects QLoRA. If memory forces QLoRA, treat it as a compromise to evaluate.
- **Context length:** Start at **2048**, then verify no rendered training examples are truncated. This corpus contains short paragraphs; 16384 does not add data coverage. Production long dictation needs representative longer examples.
- **Epochs:** Start at **1** and compare checkpoints; extend to **2** only if validation cleanup improves. Template-heavy data can be memorized quickly.
- **Learning rate:** Start at **0.0001** for LoRA, then tune against validation. It is not a universal optimum.
- **Rank / alpha:** **16 / 32** is a reasonable initial pair. Keep other model defaults unless there is evidence to change them.
- **Train on Completions:** Enable when available so loss targets the assistant output rather than copying the input. Verify nonzero supervised tokens. Seven training records have empty outputs; ensure preprocessing retains an assistant end-of-turn target for them. If the loader drops them, handle filler-only behavior separately rather than claiming it was trained.
- **Thinking/reasoning:** Keep **off**. Unsloth documents Qwen3.5 small models, including 4B, as non-thinking by default. Use the same setting and model-native chat template in training and serving. Do not insert a literal `/no_think` task prefix into each transcript.

## Serving after training

Load the trained adapter together with its matching base model, or export/load merged trained weights. Loading the original base model alone will not use the cleanup training.

Send one fresh user message per dictation and request the assistant continuation:

```json
{"messages":[{"role":"user","content":"um send it by thursday no wait friday period"}]}
```

Expected assistant content:

```text
Send it by Friday.
```

Omit the system message and task instruction in the app. Disable any serving UI's automatically injected generic assistant prompt. Keep the model's role tokens, generation marker, EOS/stop handling, and non-thinking configuration; omitting a task prompt does not mean removing the model's chat template. Some templates add fixed default text themselves; inspect the rendered prompt and keep training/serving consistent.

This training objective can teach cleanup as the model's default behavior, including treating requests as dictated words. Success is empirical, not guaranteed by removing the instruction column. Validate unseen commands/questions, meaning and number preservation, corrections, clean input, and longer speech. Compare actual generations with the held-out outputs; eval loss alone does not establish correct cleanup.

The biggest remaining limitation is example diversity: 3,000 rows come from 98 template families and 60 fixtures. To improve beyond this first experiment, add independently reviewed real-ASR examples and hard cases where the tuned model still answers, over-edits, or loses meaning.

## Sources checked

- [Unsloth Studio setup, dataset formats, splits, and training controls](https://unsloth.ai/docs/new/studio/start)
- [Unsloth Qwen3.5 training guide, precision guidance and memory estimates](https://unsloth.ai/docs/models/qwen3.5/fine-tune)
- [Unsloth Qwen3.5 inference and thinking settings](https://unsloth.ai/docs/models/qwen3.5)
- [Unsloth chat templates](https://unsloth.ai/docs/basics/chat-templates)

Regenerate exports with [export_unsloth.py](../export_unsloth.py) after editing the source splits. The original instruction/input/output files are unchanged.
