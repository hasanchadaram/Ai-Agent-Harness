# Synthetic English only; no microphone or personal recordings.
param([string]$OutputDirectory = "$PSScriptRoot/../test-data/dictation-reliability/qwen-context")
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Add-Type -AssemblyName System.Speech
foreach ($gap in @(350, 750, 1500, 20000)) {
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.Rate = 2
        $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
        $synth.SetOutputToWaveFile((Join-Path $destination "hesitation-$gap.wav"), $format)
        $ssml = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">Because right now it is giving me<break time="' + $gap + 'ms"/> some Hindi words<break time="350ms"/> in the transcript<break time="350ms"/> output sometimes.</speak>'
        $synth.SpeakSsml($ssml)
    } finally { $synth.Dispose() }
}
