@echo off
rem Estante - Conversor para MP4 (criado por: @dmwnezes)
rem Dois cliques: converte os filmes desta pasta. Ou arraste uma pasta/filmes para cima deste arquivo.
chcp 65001 >nul
title Estante - Conversor para MP4
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0converter-para-mp4.ps1" %*
echo.
pause
