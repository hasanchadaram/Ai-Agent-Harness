# Generate reproducible, non-private local speech for GPU replay on Windows.
param([string]$OutputDirectory = "$PSScriptRoot/../test-data/dictation-reliability")
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Add-Type -AssemblyName System.Speech
$texts = @{
    continuous = 'Please send the report to Alice in London on Monday. The project number is forty two and the budget is seven hundred dollars. I really really want to keep every repeated word. Open the notebook and write down the meeting time. Finally save the document and close the window.'
    repetitions = 'Very very good. I had had enough. Please please keep this word. No no no, do not delete the repetition. Alice and Bob are meeting in London on Monday. The code is one two three four five. Send twelve dollars and fifty cents.'
    short = 'Yes.'
    fast = 'When the background worker is still processing a window I release the hotkey and immediately start another sentence. Every word must remain in order, including the final word, notebook.'
}
foreach ($name in $texts.Keys) {
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if ($name -eq 'fast') { $synth.Rate = 3 }
        $synth.SetOutputToWaveFile((Join-Path $destination "$name.wav"))
        $synth.Speak($texts[$name])
    } finally { $synth.Dispose() }
}
