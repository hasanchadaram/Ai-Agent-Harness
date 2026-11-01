# Export and Hugging Face release preparation

## Checkpoint choice

The screenshot selects `unsloth_Qwen3.5-4B_1793519004` (2,400 training examples, configured sequence length 2048, final training loss about 0.2659). The latest comparison used `unsloth_Qwen3.5-4B_1793521666` (2,478 structure-aware training examples, sequence length 4096, final training loss about 0.2122).

Select the checkpoint whose actual outputs you approved. A lower training loss alone does not establish that it is better. The [model-card draft](./model-card-v2.DRAFT.md) is specifically for the newer run; do not publish it unchanged for the older run.

## Export settings

- Source: Fine-tuned, then the approved run/checkpoint.
- Export method: GGUF / llama.cpp.
- Export target: **Full model**. This merges the trained adapter before quantizing and yields a self-contained model rather than an adapter requiring a base model.
- First quant: **Q4_K_M**, approximately 2.6 GB according to the screenshot. Measure the real file after export. An optional Q5_K_M file is approximately 3.1 GB on that screen and offers a larger precision/size tradeoff to test.
- Avoid starting with IQ2/IQ3 or other very small quants until the default release works and cleanup quality has been checked.
- Importance matrix: optional for a standard Q4_K_M first export. A suitable calibration matrix can improve quantization fidelity, at the cost of extra computation. If enabling it with a selectable calibration corpus, use representative training/calibration transcripts rather than the held-out test split. Record the actual setting and corpus in the release notes.
- The image-LoRA section is unrelated to this text cleanup model.

“Full model” does not force an F16/BF16 download. Quantization reduces weight precision and file size, not the number of parameters or the maximum context length by itself. Runtime memory exceeds the file size.

No export has been initiated by this preparation. Keep the source adapter and tokenizer/template for future work; a separate source adapter release is useful but not needed for users of the merged GGUF.

## After export

1. Confirm the export completes, the file can be read, and its metadata identifies Qwen3.5, the intended quantization and the saved chat template.
2. Load the GGUF in a current Qwen3.5-compatible llama.cpp backend. For this task, supply text only; a vision projector is unnecessary for text-only inference. Do not promise universal Ollama/LM Studio compatibility without testing the specific versions.
3. Test fresh user-only requests with thinking and all tools/nudging off. Check fillers, corrections, dictated questions, lists, emails, negation, names/numbers, and clean text. Compare against the selected adapter checkpoint; do not assume conversion preserves every result.
4. Record filenames, byte sizes, SHA-256 hashes, tested runtime/build, generation settings, and hardware. Do not publish unmeasured speed, memory or accuracy claims.
5. Finalize the model card. Include upstream base, purpose, exact training corpus/split counts, actual training settings, synthetic-data limits, actual evaluation status, usage examples, quantization details and provenance. The draft already contains the verified run facts; release-specific facts still need filling.

## Minimal GGUF model repository

Create a clean release folder containing only the intended public files:

```text
README.md                     # Final model card, with YAML metadata
dictation-cleanup-qwen3.5-4b.Q4_K_M.gguf
LICENSE                       # Upstream license, plus required notices/attribution
SHA256SUMS                    # Recommended integrity information
```

Optionally add Q5_K_M, a small usage script, a changelog, measured evaluation results and a redacted training/export configuration. Preserve upstream NOTICE material if present. The GGUF should embed the tokenizer/chat metadata needed by the runtime; do not upload an entire training output directory just to supply tokenizer files.

Do not include optimizer states, private logs, tokens, local chat databases, or all intermediate checkpoints. A dataset release is optional and belongs in a separate dataset repository with its own dataset card and license. Keep held-out evaluation separate from training documentation examples.

## CLI status and commands

The available CLI executable is installed with Unsloth and is not on this shell's PATH. `hf auth whoami` through that executable confirmed account **hasanchadaram**. The installed CLI is version 1.33.0; its local help confirms the commands below. No token needs to be pasted into chat.

Suggested repository name, not yet created: `hasanchadaram/dictation-cleanup-qwen3.5-4b-gguf`.

After finalizing a clean release folder and choosing its real path, the corresponding PowerShell commands are:

```powershell
$hfCli = 'C:/Users/hasan/.unsloth/studio/unsloth_studio/Scripts/hf.exe'
$releaseRepo = 'hasanchadaram/dictation-cleanup-qwen3.5-4b-gguf'
# Set this to the prepared release folder, not the training output directory.
$releaseFolder = '<absolute-path-to-final-release-folder>'
& $hfCli repos create $releaseRepo --type model --public
& $hfCli upload $releaseRepo $releaseFolder . --type model --commit-message 'Release transcript cleanup GGUF'
```

These commands are a plan, not an executed upload. Confirm the repository name, selected checkpoint, final files and public visibility before running them. The user's current request is to review export settings and plan the release; the export and publication have not been performed.

## References

- [Unsloth Studio export](https://unsloth.ai/docs/new/studio/export)
- [Hugging Face GGUF support](https://huggingface.co/docs/hub/gguf)
- [Hugging Face model cards](https://huggingface.co/docs/hub/model-cards)
- [Hugging Face release checklist](https://huggingface.co/docs/hub/model-release-checklist)
- [Qwen3.5-4B upstream model and license](https://huggingface.co/Qwen/Qwen3.5-4B)
- [llama.cpp quantization](https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md)
