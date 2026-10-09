# Estante

App Android que transforma seus vídeos do Google Drive (e do celular) numa estante de DVDs: cada vídeo vira uma caixinha com capa e título do seu jeito.

- **Estante:** prateleiras com os DVDs em pé, separados por prateleira (Filmes, Séries, Viagens…). Busca pelo título e ordem por título, adicionados, assistidos ou do seu jeito.
- **Arrastar:** segure um DVD e arraste para outra posição ou outra prateleira (segurar sem arrastar abre a edição).
- **Temas:** madeira escura, madeira clara, metal ou locadora anos 90 (Ajustes).
- **Abrir a caixa:** ao tocar no DVD, a caixa abre e mostra o disco girando; ao tocar em Assistir, o disco acelera.
- **Capa e título:** capa da internet (TMDB, com sinopse, ano e gênero), da galeria, um quadro do próprio vídeo ou a capa automática.
- **Séries:** uma pasta do Drive vira uma caixa de série. Subpastas viram temporadas e os episódios são numerados pelo nome (S01E02, 1x02, "Episódio 3"…). O "Continuar" vai sempre para o episódio certo.
- **Pastas sincronizadas:** ligue uma pasta do Drive a uma prateleira ou série e todo vídeo novo nela entra sozinho quando você abre o app.
- **Continuar de onde parou:** posição salva a cada 5 s, ao pausar e ao sair. Começados em "Continuar assistindo", terminados com selo.
- **Listas de reprodução:** junte vídeos para tocar em sequência.
- **Player:** tela cheia, legendas .srt/.vtt (achadas na pasta do Drive ou escolhidas no celular, com acentos corrigidos), gestos (brilho à esquerda, volume à direita, toque duplo para ±10 s), janelinha flutuante e envio para a TV (Chromecast / Google TV).
- **Assistir junto:** abra uma sala a partir de um filme do Drive e mande o link. Quem abrir (até no iPhone, pelo site) assiste ao mesmo tempo, com play/pause valendo para todos, lista de quem está na sala e chat.
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

## Assistir junto

Você abre a sala no app; a outra pessoa entra pelo site **https://dmwnezes.github.io/estante/sala/** (o link já vem pronto no botão Convidar). Configuração de uma vez só:

**1. Banco do Firebase (leva play/pause e o chat entre o app e o site)**
1. Entre em [console.firebase.google.com](https://console.firebase.google.com) e toque em **Adicionar projeto**.
2. Escolha o projeto do Google Cloud que você já criou para a Estante (aparece na lista). Pode desligar o Google Analytics.
3. No menu, abra **Realtime Database** > **Criar banco de dados** > escolha o local (Estados Unidos) > **modo bloqueado**.
4. Na aba **Regras**, apague tudo, cole isto e toque em **Publicar**:
   ```json
   { "rules": { "salas": { "$sala": { ".read": true, ".write": true } } } }
   ```
5. Na aba **Dados**, copie o endereço que aparece em cima (termina em `firebaseio.com`).

**2. Chave de API do Google (para o site ler o filme do Drive)**
1. Em [console.cloud.google.com](https://console.cloud.google.com), no mesmo projeto: **APIs e serviços > Credenciais > Criar credenciais > Chave de API**.
2. Toque na chave criada para restringir: em **Restrições de aplicativos** escolha **Referenciadores HTTP** e adicione `dmwnezes.github.io/*`; em **Restrições de API** escolha só a **Google Drive API**. Salve e copie a chave (começa com `AIza`).

**3. Ligar o site no GitHub**
No repositório: **Settings > Pages > Build and deployment > Deploy from a branch**, branch **main**, pasta **/docs**, **Save**. Em um ou dois minutos o site fica no ar.

**4. Compartilhar os filmes**
No Drive, compartilhe a pasta dos filmes como **Qualquer pessoa com o link · Leitor** (assim vale para todos de uma vez). Sem isso o iPhone não tem permissão de ler o arquivo. O app avisa se o filme ainda não estiver compartilhado.

**5. No app**
**Ajustes > Assistir junto**: cole o endereço do banco e a chave, escreva seu nome e toque em **Salvar e testar**. Depois é só abrir um filme do Drive e tocar em **Assistir junto**.

**Formatos:** o Safari do iPhone toca **MP4** e **MOV** (H.264 ou HEVC). MKV e AVI não abrem no iPhone; o app avisa antes.

## Capas da internet (opcional)

1. Crie uma conta grátis em [themoviedb.org](https://www.themoviedb.org/signup).
2. Em **Configurações > API**, peça uma chave de uso pessoal.
3. Copie a **Chave da API** (ou o **Token de leitura**) e cole em **Ajustes > Capas da internet** no app.

Com a chave, os vídeos novos já ganham pôster, sinopse, ano e gênero quando o nome do arquivo bate com um filme ou série. Vídeos caseiros ficam com a miniatura do próprio vídeo.

## TV (Chromecast)

O botão de TV aparece no player quando há um Chromecast ou TV com Google Cast na mesma rede Wi-Fi. O celular serve o vídeo para a TV pela rede de casa, então ele precisa ficar ligado e no mesmo Wi-Fi enquanto a TV toca. O formato mais seguro para a TV é MP4.

## Formatos

O player usa o ExoPlayer do Android. MP4 (H.264/H.265) e MKV funcionam melhor; AVI e alguns codecs antigos podem não tocar.

## Estrutura

```
app/src/main/java/com/dmwnezes/estante/
├── MainActivity.kt        navegação entre as telas
├── AppGraph.kt            peças compartilhadas (Drive, estante, capas)
├── data/                  estante (JSON), séries, retomada, capas, TMDB, importação e sincronização
├── drive/                 login do Google (só leitura) e API do Drive
├── player/                player, legendas, gestos, janelinha e envio para a TV
├── party/                 assistir junto: Firebase (REST), sincronia, sala e chat
├── update/                atualização pelas Releases do GitHub
└── ui/                    estante, DVD, tábua, listas, Drive, ajustes e abertura
```

O site da sala fica em `docs/sala/index.html` (GitHub Pages).

Cada `push` na branch `main` roda os testes, compila o APK no GitHub Actions e publica em **Releases**.
