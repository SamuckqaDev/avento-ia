# Diagnostica erros de build, testes ou execução usando logs reais
Gatilhos: investigar erro, investigue erro, diagnosticar erro, diagnostique erro, build falhando, teste falhando, projeto nao sobe, projeto não sobe

Procedimento:
1. Leia a mensagem de erro fornecida e localize os arquivos envolvidos com search_files e read_file.
2. Se houver processo gerenciado, use terminal_list e terminal_logs antes de iniciar outro.
3. Execute o menor comando de reprodução permitido com terminal_run; para processo longo use terminal_start e terminal_logs.
4. Diferencie causa raiz, sintomas e avisos sem impacto.
5. Quando o usuário pediu correção, aplique a menor mudança possível e repita a validação.

Não chute a causa e não diga que o serviço subiu sem log ou resultado da ferramenta confirmando.
