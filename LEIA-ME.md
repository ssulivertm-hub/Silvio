# Gravador de Corridas v1.1 (Android)

Liga a gravação sozinho quando a corrida começa e desliga 20 s depois que termina (99, inDrive; Uber opcional).

## Novidades da 1.1
- Resolução: 240p, 480p, 720p, 1080p, 4K (o app usa o maior tamanho que a câmera aceitar e registra no log o tamanho real; se 4K falhar, tenta 720p).
- Vídeo: 320, 820, 1500, 3000, 6000, 12000, 25000 kbps. Áudio: 32, 48, 64, 96, 128, 144 kbps. FPS: 15, 24, 30.
- Na primeira abertura pergunta onde salvar (galeria ou outra pasta, ex.: cartão SD). Depois: botão "Escolher pasta de gravação".
- Google Drive: ao terminar cada gravação, envia o arquivo para a pasta "Corridas" do seu Drive, por Wi-Fi ou dados móveis. Se estiver sem rede, fica na fila e vai no próximo envio.

## Gerar o APK (sem instalar nada no PC)
    unzip gravador-corridas.zip && cd gravador-corridas
    git init -b main && git add . && git commit -m app
    gh repo create gravador-corridas --private --source . --push
    gh run watch            # ~5 min
    gh run download -n gravador-apk
    adb install app-debug.apk     # ou copie o APK para o celular

Se o build falhar: `gh run view --log-failed` e me mande o erro.

## Configurar no celular (uma vez)
1. Botão 1: permissões. Botão 2: Info do app > ⋮ > "Permitir configurações restritas".
2. Botão 3: Acessibilidade > Gravador de Corridas > ligar. Botão 4: bateria sem restrição (e autostart no Realme).
3. Botão REC flutuante: toque = iniciar/parar manualmente (sempre funciona).
4. Ajuste qualidade e pasta, toque em SALVAR TODAS AS CONFIGURAÇÕES.

## Google Drive (uma vez, ~10 min, no navegador)
1. console.cloud.google.com > criar projeto > "APIs e serviços" > ativar **Google Drive API**.
2. "Google Auth Platform": tipo de usuário Externo, adicione seu e-mail como usuário de teste.
   **Depois publique o app ("Em produção")**: em modo Teste, o Google expira a autorização a cada 7 dias.
   O aviso "app não verificado" pode aparecer: Avançado > continuar (o app só pede o escopo drive.file,
   que só enxerga arquivos criados por ele).
3. Clientes > Criar cliente > tipo **"TVs e dispositivos de entrada limitada"**. Copie Client ID e Client secret.
4. No app: cole os dois, marque "Enviar cada gravação ao Google Drive", SALVAR, toque em "Conectar ao Google Drive",
   abra google.com/device, digite o código mostrado e toque em Permitir.

## Limites (honestos)
- "Direto na nuvem" aqui = enviar o arquivo assim que a gravação termina. Gravar ao vivo dentro do Drive não é
  possível com MP4 (o arquivo só fica válido ao final). Se o celular morrer no meio da corrida, o trecho gravado até lá fica no aparelho.
- Vídeo de 4K a 25 Mbps ≈ 11 GB/hora: pense no plano de dados e no espaço.
- Escolher uma pasta do próprio Drive como destino (seletor do Android) não é recomendado: o MP4 precisa de arquivo local.
- Os textos de detecção das telas da 99 e do inDrive são palpites: use o modo calibração e ajuste.
- Não pude compilar nem testar neste ambiente.


## Alterações preparadas na versão 1.2
- Duração máxima selecionável por gravação: 10, 20, 30, 40, 50 minutos, 1, 2 ou 3 horas.
- O serviço de gravação usa a duração selecionada e aplica novamente esse limite quando uma parada automática é cancelada.
- A lista inicial de apps monitorados também considera o Uber (além de 99 e inDrive).
- Painel flutuante experimental que tenta ler valor, distância e tempo presentes na tela e calcula R$/km e R$/h.
- Quando identifica um texto de destino, o painel permite abrir a pesquisa desse destino no Google Maps.

## Limitações desta versão
- O painel usa leitura de acessibilidade e padrões genéricos de texto. Uber, 99 e inDrive podem mudar seus layouts ou ocultar dados; por isso, valor, distância, tempo e endereço precisam ser testados em aparelhos e versões reais dos apps.
- A integração atual abre o destino no Google Maps. Ela ainda não incorpora um mapa em miniatura dentro do painel, nem garante rua, número e bairro se esses dados não estiverem expostos na tela da oferta.
- Não existe API pública comum que permita obter todos os detalhes de ofertas dos três aplicativos. Não há garantia de leitura de conteúdo que os próprios apps não exponham à acessibilidade.
- Este ambiente não possui Android SDK/Gradle instalado e não permite validar uma compilação Android. O ZIP contém o código atualizado, não um APK compilado. O workflow do GitHub Actions já existente pode gerar o APK após o projeto ser enviado a um repositório GitHub.
