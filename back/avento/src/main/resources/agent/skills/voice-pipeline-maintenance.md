# Diagnostica e melhora entrada e saída de voz local do Avento
Gatilhos: corrigir voz avento, voz robotizada, whisper falhou, piper falhou, avento nao para de falar, avento não para de falar

1. Rastreie captura, formato, conversão FFmpeg, Whisper, idioma, preparação TTS, Piper e playback.
2. Verifique binários, modelos, permissão, arquitetura, dylibs, sample rate e limpeza de temporários.
3. Preserve o idioma detectado salvo pedido explícito e selecione uma voz com fonemas compatíveis.
4. Remova Markdown, código, URLs, descrição de emoji e metadados antes de sintetizar.
5. Divida em frases naturais, transmita progressivamente e mantenha uma fila cancelável por chat.
6. Pare ao clicar stop, começar gravação, trocar de chat, fazer logout ou iniciar resposta nova.
7. Teste português e inglês, interrupção, repetição e recuperação de erro.

Não registre áudio privado nem credenciais.
