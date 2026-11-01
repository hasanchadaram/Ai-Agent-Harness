# Notation-aware dictation cleanup, version 3

Upload [train.jsonl](./train.jsonl) for the next Unsloth experiment. Use `messages` conversations (`auto`/`chatml`), the model-native chat template, no added system/task prompt, thinking off, and no tools or tool nudging during inference. The training file already contains the earlier mixed cleanup and formatting examples; do not also upload version 2 as extra training data.

This is a dataset update, not a trained-model update. No training, GGUF export, Hub weight upload, or public model-card change has been performed for this version. Keep the released model and previous checkpoint as comparison baselines.

## Counts

| Split | Existing v2 examples retained | New notation examples | Total | Existing labels revised |
|---|---:|---:|---:|---:|
| Train | 2,478 | 93 | 2,571 | 10 |
| Validation | 320 | 20 | 340 | 25 |
| Test | 320 | 20 | 340 | 25 |

There are **133 new individually specified synthetic examples** and **60 narrowly revised existing labels** across the corpus. There are 3,251 distinct inputs overall. All old inputs retain their split membership; close variations and contrast families stay within one split. Version 2 files are unchanged. The new fixture categories include hyphens, parentheses, square brackets, curly braces, contextual numerals, literal words, and already correct text.

## Files

- [train.jsonl](./train.jsonl): 2,571 training conversations, without a system instruction.
- [validation.jsonl](./validation.jsonl): 340 examples for checkpoint selection.
- [test.jsonl](./test.jsonl): 340 examples for final evaluation.
- [master.json](./master.json): all splits in instruction/input/output form for editing and review; do not train on it when evaluating against the separate splits.
- [notation_additions.json](./notation_additions.json): all 133 new examples with category, family and split annotations. Review rather than uploading it alongside the mixed training file.
- [label_changes.json](./label_changes.json): all 60 old/new label changes and their reasons.
- [metadata.json](./metadata.json): provenance, family, split, input hash and relabel status.
- [validation_report.json](./validation_report.json): counts, original source hashes and structural verification scope.
- [build_dataset.py](./build_dataset.py): deterministic, standard-library build script; reads version 2 and writes this version.
- [LABELING_POLICY.md](./LABELING_POLICY.md): rules used to author the targets. This document is for dataset editing, not an automatically applied system prompt.

## Reproduction examples

| Raw input | Target output |
|---|---|
| `local agreement hyphen two` | `Local agreement-2` |
| `local agreement dash two` | `Local agreement-2` |
| `local agreement-2` | `Local agreement-2` |
| `It's good in brackets it's not good` | `It's good (it's not good).` |
| `print five copies of the agenda` | `Print 5 copies of the agenda.` |
| `the word hyphen appears in the instructions` | `The word "hyphen" appears in the instructions.` |
| `she is one of a kind` | `She is one of a kind.` |

If ASR sends `local agreement two`, it has not explicitly supplied a hyphen. The conservative target is `Local agreement 2`, not an invented joiner. To guarantee a specific private document spelling, give the application a vocabulary/replacement rule or preserve ASR punctuation. Train on the exact ASR string the cleanup stage actually receives, rather than an idealized version of the audio.

Unspecified trailing “in brackets” is interpreted as parentheses around the clearly supplied final aside. Mid-sentence closure needs an explicit close marker or otherwise unambiguous scope. We do not claim arbitrary missing delimiters can always be reconstructed reliably.

## Instructions or examples?

For this application's no-system-prompt goal, use the labeling policy to write consistent examples and train those exact user/assistant pairs. Adding text to the `instruction` field in a master file will not affect these conversational exports, which deliberately omit that field. Updating a model card will not change the weights or behavior either.

A runtime instruction is an alternative for quick experimentation if the app is willing to send one. It is not a substitute for testing the no-instruction configuration. Avoid training with a newly added prompt and then silently removing it at inference; keep training and serving formats aligned.

Correcting conflicting earlier labels matters as much as adding examples. Version 2 explicitly allowed some small counts to remain words. Version 3 prefers numerals in quantitative contexts and revises the affected old labels instead of presenting both policies as equally canonical.

## Training and evaluation

For a clean comparison, train a new adapter from the same original base used for version 2 with this mixed file, retaining the previous successful adapter. Enable assistant/completion-only loss if supported and confirm the masking leaves valid assistant targets, including end-of-turn targets for empty outputs. There is no need to change every hyperparameter at once. Continue to use tools-off, non-thinking, fresh-transcript inference.

Evaluate on the held-out additions and fresh real-ASR examples as well as on existing cleanup behavior. The user's reproduction phrases are now in training, so getting those right is a regression check, not proof of broad generalization. Because 50 older held-out labels were reannotated to the v3 numeral policy, v2 and v3 exact-match scores are not directly comparable unless both models are scored against the same labeled benchmark version. Preserve both versions for transparent comparisons.

Checks cover JSON validity and round-tripping, unique inputs, original split membership, family separation, source hashes, no system messages, representative relabeling boundaries, required hyphens, and balanced delimiters in the relevant new categories. These are not model-quality measurements or independent human validation. All new labels are AI-authored; review them before training. The earlier corpus remains mostly template-generated, and there is no claim that the new examples alone will guarantee accurate notation.

Source guidance: [Unsloth datasets guide](https://unsloth.ai/docs/get-started/fine-tuning-llms-guide/datasets-guide), [Unsloth chat templates](https://unsloth.ai/docs/basics/chat-templates).
