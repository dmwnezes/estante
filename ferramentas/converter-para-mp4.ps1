<#
  Estante — Conversor para MP4
  Transforma filmes MKV, AVI, WMV… em MP4 que tocam no iPhone (site da sala) e no app.

  Como usar: coloque este arquivo e o "Converter para MP4.bat" na pasta dos filmes e
  dê dois cliques no .bat — ou arraste uma pasta (ou alguns filmes) para cima do .bat.

  - Os originais NÃO são apagados nem alterados.
  - Os convertidos vão para a subpasta "convertidos".
  - Quando dá, só troca a "caixa" (rápido, sem perder qualidade); senão converte o vídeo.
  - Prefere o áudio em português e tira a legenda embutida para um .srt com o mesmo nome
    (o app acha a legenda sozinho quando ela está ao lado do filme no Drive).

  criado por: @dmwnezes
#>
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Itens)

$ErrorActionPreference = "Continue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$Extensoes = @(".mkv", ".avi", ".wmv", ".flv", ".webm", ".m2ts", ".ts", ".mpg", ".mpeg", ".divx", ".ogm", ".rmvb", ".3gp")

function Escrever($texto, $cor = "Gray") { Write-Host $texto -ForegroundColor $cor }

function Achar-Ffmpeg {
    if (Get-Command ffmpeg -ErrorAction SilentlyContinue) { return $true }
    # Pasta "ffmpeg" ao lado do script (quem baixou o ffmpeg na mão).
    $local = Join-Path $PSScriptRoot "ffmpeg\bin"
    if (Test-Path (Join-Path $local "ffmpeg.exe")) { $env:Path = "$local;$env:Path"; return $true }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { return $false }
    Escrever "O ffmpeg (programa gratuito que faz a conversão) não está instalado. Instalando pelo winget…" Yellow
    try { winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements | Out-Host } catch {}
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User") + ";" + (Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links")
    return [bool](Get-Command ffmpeg -ErrorAction SilentlyContinue)
}

function Ler-Info($arquivo) {
    $json = & ffprobe -v error -show_streams -show_format -of json "$arquivo" 2>$null | Out-String
    if (-not $json) { return $null }
    return $json | ConvertFrom-Json
}

function Idioma($s) {
    $l = ""
    if ($s.tags -and $s.tags.language) { $l = [string]$s.tags.language }
    $t = ""
    if ($s.tags -and $s.tags.title) { $t = [string]$s.tags.title }
    if ($l -match '^(por|pt|pob|pt-br)$' -or $t -match '(?i)portugu|dublad|pt-?br') { return 2 }
    if ($s.disposition -and $s.disposition.default -eq 1) { return 1 }
    return 0
}

function Melhor($lista) {
    $melhor = $null; $nota = -1
    foreach ($s in $lista) { $n = Idioma $s; if ($n -gt $nota) { $melhor = $s; $nota = $n } }
    return $melhor
}

function Converter($arquivo, $saidaDir) {
    $nome = [System.IO.Path]::GetFileNameWithoutExtension($arquivo)
    $saida = Join-Path $saidaDir "$nome.mp4"
    if (Test-Path -LiteralPath $saida) { Escrever "  já convertido, pulando." DarkGray; return "pulado" }

    $info = Ler-Info $arquivo
    if (-not $info) { Escrever "  não consegui ler este arquivo." Red; return "erro" }
    $videos = @($info.streams | Where-Object { $_.codec_type -eq "video" -and -not ($_.disposition -and $_.disposition.attached_pic -eq 1) })
    $audios = @($info.streams | Where-Object { $_.codec_type -eq "audio" })
    $legendas = @($info.streams | Where-Object { $_.codec_type -eq "subtitle" -and @("subrip", "ass", "ssa", "mov_text", "webvtt", "text") -contains $_.codec_name })
    if ($videos.Count -eq 0) { Escrever "  sem vídeo, pulando." DarkGray; return "pulado" }
    $v = $videos[0]
    $a = Melhor $audios

    $ff = @("-hide_banner", "-loglevel", "error", "-stats", "-y", "-i", $arquivo, "-map", "0:$($v.index)")
    $pix = [string]$v.pix_fmt
    if ($v.codec_name -eq "h264" -and $pix -eq "yuv420p") {
        $ff += @("-c:v", "copy"); $modo = "rápido (sem reconverter o vídeo)"
    } elseif ($v.codec_name -eq "hevc" -and ($pix -eq "yuv420p" -or $pix -eq "yuv420p10le")) {
        $ff += @("-c:v", "copy", "-tag:v", "hvc1"); $modo = "rápido (sem reconverter o vídeo)"
    } else {
        $ff += @("-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2")
        $modo = "convertendo o vídeo ($($v.codec_name)) — pode demorar"
    }
    if ($a) {
        $ff += @("-map", "0:$($a.index)")
        if ($a.codec_name -eq "aac" -and [int]$a.channels -le 2) { $ff += @("-c:a", "copy") }
        else { $ff += @("-c:a", "aac", "-b:a", "192k", "-ac", "2") }
    }
    $ff += @("-sn", "-dn", "-map_chapters", "-1", "-movflags", "+faststart", $saida)

    Escrever "  $modo" DarkCyan
    & ffmpeg @ff
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $saida)) {
        if (Test-Path -LiteralPath $saida) { Remove-Item -LiteralPath $saida -Force }
        Escrever "  deu erro na conversão." Red
        return "erro"
    }

    # Legenda: a de fora (mesmo nome) ou a embutida, de preferência em português.
    $srt = Join-Path $saidaDir "$nome.srt"
    $fora = @(".srt", ".pt-BR.srt", ".pt.srt", ".por.srt") | ForEach-Object { Join-Path (Split-Path $arquivo) "$nome$_" } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($fora) { Copy-Item -LiteralPath $fora $srt -Force; Escrever "  legenda copiada." DarkCyan }
    elseif ($legendas.Count -gt 0) {
        $l = Melhor $legendas
        & ffmpeg -hide_banner -loglevel error -y -i $arquivo -map "0:$($l.index)" -c:s srt $srt
        if ($LASTEXITCODE -eq 0) { Escrever "  legenda embutida salva em .srt." DarkCyan }
    }
    return "ok"
}

# ---------------------------------------------------------------- início ---
Escrever ""
Escrever "  ESTANTE · Conversor para MP4" Magenta
Escrever "  deixa seus filmes prontos para o iPhone e para a sala" DarkGray
Escrever ""

if (-not (Achar-Ffmpeg)) {
    Escrever "Não encontrei o ffmpeg." Red
    Escrever "Instale em https://www.gyan.dev/ffmpeg/builds/ (ffmpeg-release-essentials.zip)," Yellow
    Escrever "descompacte e coloque a pasta com o nome 'ffmpeg' ao lado deste arquivo. Depois rode de novo." Yellow
    exit 1
}

if (-not $Itens -or $Itens.Count -eq 0) { $Itens = @($PSScriptRoot) }
$arquivos = @()
foreach ($i in $Itens) {
    if (-not $i) { continue }
    $i = $i.Trim('"')
    if (Test-Path -LiteralPath $i -PathType Container) {
        $arquivos += Get-ChildItem -LiteralPath $i -File | Where-Object { $Extensoes -contains $_.Extension.ToLower() }
    } elseif (Test-Path -LiteralPath $i -PathType Leaf) {
        $f = Get-Item -LiteralPath $i
        if ($Extensoes -contains $f.Extension.ToLower()) { $arquivos += $f }
    }
}
if ($arquivos.Count -eq 0) {
    Escrever "Nenhum filme para converter aqui (procuro: $($Extensoes -join ', '))." Yellow
    Escrever "MP4 e MOV já tocam no iPhone e não precisam de conversão." DarkGray
    exit 0
}

$ok = 0; $erros = 0; $pulados = 0; $n = 0
foreach ($f in $arquivos) {
    $n++
    $saidaDir = Join-Path $f.DirectoryName "convertidos"
    if (-not (Test-Path -LiteralPath $saidaDir)) { New-Item -ItemType Directory -Path $saidaDir | Out-Null }
    Escrever "[$n/$($arquivos.Count)] $($f.Name)" White
    $r = Converter $f.FullName $saidaDir
    if ($r -eq "ok") { $ok++; Escrever "  pronto!" Green } elseif ($r -eq "erro") { $erros++ } else { $pulados++ }
}

Escrever ""
Escrever "Terminado: $ok convertido(s), $pulados pulado(s), $erros com erro." Magenta
if ($ok -gt 0) {
    Escrever "Os MP4 estão na pasta 'convertidos'. Suba para o Drive (com o .srt junto, se tiver)" Gray
    Escrever "e, no app, troque o arquivo do DVD ou importe a pasta de novo." Gray
}
