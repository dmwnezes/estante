# Estante

App Android que transforma seus vídeos do Google Drive (e do celular) numa estante de DVDs: cada vídeo vira uma caixinha com capa e título do seu jeito.

- **Estante:** prateleiras de madeira com os DVDs em pé, separados por prateleira (Filmes, Séries, Viagens…) e ordenados por título, adicionados ou assistidos recentemente.
- **Capa e título:** escolha a capa da galeria, use um quadro do próprio vídeo ou deixe a capa automática. Segure um DVD para editar direto.
- **Continuar de onde parou:** o app salva a posição a cada 5 s, ao pausar e ao sair. Os vídeos começados aparecem em "Continuar assistindo"; os terminados ganham um selo.
- **Listas de reprodução:** junte vídeos para tocar em sequência, reordene e toque tudo ou a partir de qualquer item. Cada vídeo da lista também continua de onde parou.
- **Player:** tela cheia deitada, avançar/voltar 10 s, velocidade, legendas embutidas (MKV), próximo/anterior na lista.
- **Google Drive:** navegue pelas pastas, "Compartilhados comigo" ou busque pelo nome. Marque vários vídeos ou toque em "Tudo" para trazer uma pasta inteira. Os vídeos tocam direto do Drive, sem baixar.
- **Atualização dentro do app:** Ajustes > Buscar atualização.

## Instalar no celular

**https://github.com/dmwnezes/estante/releases/latest/download/Estante.apk**

O Android vai pedir para permitir a instalação de apps desta fonte. Aceite e instale. Novas versões instalam por cima da anterior.

## Configurar o Google Drive (uma vez só)

O Google só deixa um app ler o Drive depois que ele é registrado num projeto do Google Cloud. É grátis.

1. Entre em [console.cloud.google.com](https://console.cloud.google.com) com a conta do Drive e crie um projeto (ex.: **Estante**).
2. Em **APIs e serviços > Biblioteca**, procure **Google Drive API** e toque em **Ativar**.
3. Em **APIs e serviços > Tela de consentimento OAuth** (ou **Google Auth Platform**): tipo **Externo**, nome **Estante**, seu e-mail de suporte e de contato. Salve.
4. Em **Público-alvo**, adicione seu e-mail como **usuário de teste**. (Se quiser evitar que o Google peça para entrar de novo a cada 7 dias, toque em **Publicar app** — ele continua só seu.)
5. Em **Acesso a dados / Escopos**, adicione `https://www.googleapis.com/auth/drive.readonly`.
6. Em **Credenciais > Criar credenciais > ID do cliente OAuth**, escolha **Android** e preencha:
   - **Nome do pacote:** `com.dmwnezes.estante`
   - **Impressão digital SHA-1:** `60:2D:21:28:06:58:E5:71:2F:97:C6:38:0D:E4:35:42:C7:92:6C:0C`

   (Os dois dados também aparecem no app, em **Ajustes**, prontos para copiar.)
7. Abra a Estante, toque em **Adicionar > Do Google Drive > Conectar Google Drive** e permita o acesso. Como o app não passou pela verificação do Google, aparece o aviso "O Google não verificou este app": toque em **Avançado > Acessar Estante**.

A permissão é só de **leitura**: o app lista e toca os vídeos, nunca altera nem apaga nada no Drive.

## Formatos

O player usa o ExoPlayer do Android. MP4 (H.264/H.265) e MKV funcionam melhor; AVI e alguns codecs antigos podem não tocar.

## Estrutura

```
app/src/main/java/com/dmwnezes/estante/
├── MainActivity.kt        navegação entre as telas
├── AppGraph.kt            peças compartilhadas (Drive, estante, capas)
├── data/                  estante (JSON), regras de retomada, capas e importação
├── drive/                 login do Google (só leitura) e API do Drive
├── player/                player em tela cheia com fila e retomada
├── update/                atualização pelas Releases do GitHub
└── ui/                    estante, DVD, tábua, listas, Drive, ajustes e abertura
```

Cada `push` na branch `main` roda os testes, compila o APK no GitHub Actions e publica em **Releases**.
