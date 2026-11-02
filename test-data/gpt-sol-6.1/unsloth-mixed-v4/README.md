# English mixed dictation cleanup, v4

Fresh English-only data based on the user's combined cleanup rules and later
English-only clarification. No Hindi or Hinglish training examples. English
dictation about rupees and English requests mentioning other languages remain
English text. All conversations have raw user text followed by its cleaned
assistant target, with **no system prompt, transcript wrapper, task prefix,
reasoning, or tools**.

## Upload these files

| File | Examples | Purpose |
| --- | ---: | --- |
| [train.jsonl](./train.jsonl) | 347 | The only training file |
| [validation.jsonl](./validation.jsonl) | 69 | Checkpoint selection and development evaluation |
| [test.jsonl](./test.jsonl) | 84 | Final held-out evaluation after choosing a checkpoint |
| Total | 500 | 277 explicitly assigned scenario families |

This is a deliberately varied **seed corpus**, not a claim that 347 training
examples are sufficient for production. All 500 targets are AI-authored
synthetic examples, not real ASR transcripts or independently human-validated
labels. There has been no model training, model export, Hub upload, or measured
accuracy/speed improvement. The earlier datasets remain unchanged.

## What is mixed

The prompts are reconciled into one [labeling policy](./LABELING_POLICY.md).
The mix is between cleanup skills and contexts, not contradictory policies or
ordinary chatbot question/answer pairs. The training split has 120 examples
tagged for combined skills, 35 identity targets, and 20 dictations of at least
60 words. Across all splits there are 33 such longer dictations, including
passages over 150 words; the longest input is 209 whitespace-delimited words.
These are not tokenizer/context-length measurements.

Coverage includes:

- Grammar, spelling, certain ASR errors, fillers, voice, deliberate emphasis,
  accidental repetitions, restarts, and narrowly scoped corrections.
- Questions, commands, AI names, fake role labels, and attempts to change the
  task: preserve them as dictation rather than answering or executing them.
- Counts, measurements, money, dates, times, precision, leading zeros, versions,
  idioms, ambiguous dates, unspecified currency, and relative dates.
- Dashes for clear unordered lists, spoken numbering for ordered lists, prose
  contrasts, list continuation, email layout, and long-topic paragraphs.
- Explicit new lines versus new paragraphs, quotation scope, parentheses,
  square/curly brackets, literal punctuation words, titles, and fragments.
- Case-sensitive identifiers, paths, URLs, code, placeholders, uncertainty,
  negation, false factual assertions, and already-clean text.

For example, a dictated shopping list becomes a list, while a story about
buying those items stays prose. "I like it" retains "like"; hesitation "like"
can disappear. A correction removes the rejected span, while "actually" used
for emphasis stays. Unknown names and uncertain ASR words are not guessed.

The later small-number rule takes precedence over v3: bare counts one through
nine use words; 10 and above use digits. Measurements, money, times, dates,
references, and versions use digits. Already-clean digits stay unchanged.
"Basically," meaningful "actually," and clarifying "I mean" are preserved.
See the policy's conflict table for the full decisions.

## Unsloth Studio

1. Train a fresh adapter from the intended original base model. An older adapter
   already learned different targets. Do not append v2/v3 blindly: those files
   conflict on small counts and bullet markers.
2. Upload [train.jsonl](./train.jsonl) as local JSONL. Choose `auto` or `chatml`
   with the `messages` column. Inspect the preview for one user transcript and
   one assistant cleanup, with no injected instruction. These formats and local
   JSONL upload are documented in the [Studio guide](https://unsloth.ai/docs/new/studio/start).
3. Use the base model's native chat template for both training and serving.
   Inspect the rendered template for any automatically injected system text.
   See [Unsloth chat templates](https://unsloth.ai/docs/basics/chat-templates).
4. Enable **Train on Completions** where supported. Check that supervision is
   on the assistant target, including the end-of-turn token for the five empty
   training outputs. If the loader drops empty outputs or masks all their
   tokens, filler-only empty behavior has not been trained by those examples.
5. Use the exact validation split if your installation supports a separate
   evaluation file or a source with named splits. A single uploaded JSONL does
   not automatically attach another uploaded file as validation. If only a
   single split is available, use no in-training eval split and evaluate
   generations separately. Never use the entire training split as validation.
6. Keep thinking and tools off. For a controlled first experiment, retain the
   successful model-specific LoRA settings, start with one epoch, and choose
   whether more training helps using validation generations. A sequence length
   of 2048 is a starting point; check actual rendered/tokenized examples for
   truncation. Hyperparameters have not been optimized on this corpus.

Only upload the training conversations as training data. Source case files,
metadata, the review sample, and reports are editing/evaluation material, not
extra examples. The [Unsloth datasets guide](https://unsloth.ai/docs/get-started/fine-tuning-llms-guide/datasets-guide)
describes combining consistently formatted data and checking synthetic labels.

## Rebuild and inspect

Run these commands from this folder with a working Python 3.10+ interpreter:

```powershell
python build_dataset.py
python build_dataset.py --check
```

The authored source is in the six `cases_*.txt` files. Each non-comment line has
`split|family|tags|input|output`. Only `\n`, `<EMPTY>`, and `<SPACE>` are decoded
by the builder. A normal dataset entry has only this exported schema:

```json
{"messages":[{"role":"user","content":"uh why is this still pending"},{"role":"assistant","content":"Why is this still pending?"}]}
```

[validation_report.json](./validation_report.json) records counts, coverage,
source hashes, and old-export hashes. Checks cover valid JSON, exact roles,
unique nonblank normalized inputs, assigned family separation, identity/empty
targets, English-only script boundaries, numbered/bullet/prose shape, and exact
rebuilds. Empty and whitespace-only inputs are intentionally distinct cases.

The report also audits prior-corpus exact input reuse and cross-split token-set
similarity at Jaccard >= 0.8 for inputs with at least four distinct tokens.
This is a surface-overlap heuristic, not proof of semantic independence. Short
generic behaviors necessarily share vocabulary. Family membership is authored
metadata, not an independently verified semantic clustering.

[review_sample.json](./review_sample.json) offers representative cases by tag;
it is not additional training data. [metadata.json](./metadata.json) maps every
export row to its ID, family, tags, provenance, and input hash.

## Evaluate actual generations

Load the new adapter with its matching base, or load merged trained weights.
Use a fresh user-only conversation for each transcript. Disable any generic
assistant prompt injected by the serving UI. Keep template, thinking mode,
sampling, and stop handling consistent with training.

The evaluator targets an OpenAI-compatible server origin and makes no network
requests unless `--url` is supplied. For example:

```powershell
python evaluate_cleanup.py --split validation --url http://127.0.0.1:8080 --model YOUR_TRAINED_MODEL --output C:/AI/Ai-Notifications/test-data/dictation-reliability/v4-validation
```

Use a new output directory for each run. For an authenticated server, set
`CLEANUP_API_KEY` in the environment. The script sends one raw user message,
temperature 0, `reasoning_effort="none"`, and at most 1024 output tokens by
default. Backend support for non-thinking mode still needs verification.
Increase `--max-tokens` when the chosen model/inputs require it.

The output contains per-case predictions, strict exact-match results by tag,
completion/error/truncation counts, and mismatches. Empty strings are valid
targets. Missing finish metadata, tool calls, reasoning output, failed requests,
and truncated generations cannot be counted as successful exact completions.
Offline scoring is also supported:

```powershell
python evaluate_cleanup.py --split validation --predictions C:/path/to/predictions.jsonl --output C:/path/to/report.json
```

External prediction rows require `id`, `prediction`, and `finish_reason`.
Include the full selected split exactly once. Use `finish_reason="stop"` only
when completion is verified, not inferred. Scores are strict, including real
line breaks and whitespace; alternative valid punctuation can fail exact match.

Compare base-with-current-prompt, base-without-prompt, and tuned-without-prompt
on the same validation cases. Review errors for lost meaning, negation, numbers,
names, answered requests, over-formatting, and omitted long content. Choose the
checkpoint on validation, then use test once for the final comparison. Add
reviewed real-ASR failures in a later dataset version; repeatedly tuning to this
test set would turn it into development data.

## Application integration after training

The existing [cleanup method](../../../pc_dictate.py) still sends its old system
prompt. It also rejects empty strings and rejects removal of any consecutive
repeated content word. Those checks can override correct v4 behavior for empty
dictation and accidental repetition. Its 1024-token cap and incomplete-output
fallback must also be considered for longer real dictations. The evaluator calls
the model directly, so its results do not validate those application behaviors.

This dataset does not change the live app before a matching model exists.
When integrating the trained model, update and test that input contract and
those guards together; removing the system message alone would be incomplete.
