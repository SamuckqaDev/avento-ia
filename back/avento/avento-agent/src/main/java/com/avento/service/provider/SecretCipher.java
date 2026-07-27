package com.avento.service.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra os segredos que vão para o banco — hoje, a chave de API do provedor de nuvem.
 *
 * <p>Guardar a chave em texto puro faria um dump do banco, um backup ou um log de query vazarem
 * acesso pago à conta do usuário. AES-GCM porque autentica além de cifrar: texto adulterado falha
 * ao decifrar em vez de virar lixo silencioso.
 *
 * <p>A chave de cifra vem de configuração. O padrão deriva do segredo de JWT que o projeto já exige,
 * para funcionar sem configuração extra; trocar qualquer um dos dois torna os segredos gravados
 * ilegíveis — e nesse caso {@link #decrypt} devolve vazio em vez de explodir, para o usuário apenas
 * reconfigurar a chave em vez de encontrar a aplicação quebrada.
 */
@Component
public class SecretCipher {

    private static final Logger logger = LoggerFactory.getLogger(SecretCipher.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String PREFIX = "enc:v1:";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(
            @Value("${avento.provider.encryption-secret:}") String encryptionSecret,
            @Value("${avento.auth.jwt-secret:avento-local-fallback-secret}") String jwtSecret) {
        String source = encryptionSecret == null || encryptionSecret.isBlank() ? jwtSecret : encryptionSecret;
        this.key = new SecretKeySpec(sha256(source), "AES");
    }

    /** Devolve o texto cifrado com prefixo de versão, ou o próprio valor quando vazio. */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            // Nunca loga o valor: a mensagem de erro de cifra pode ecoar o conteudo.
            logger.error("Falha ao cifrar segredo: {}", exception.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * Decifra. Devolve vazio quando o valor não está cifrado, está corrompido, ou foi gravado com
     * outra chave — o caminho de chamada trata isso como "não configurado" e pede para reconfigurar.
     */
    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            return "";
        }
        if (!storedValue.startsWith(PREFIX)) {
            // Valor gravado antes desta versão: trata como ausente em vez de devolver texto puro.
            logger.warn("Segredo gravado sem cifra encontrado; sera ignorado ate ser reconfigurado");
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            logger.warn(
                    "Nao foi possivel decifrar o segredo ({}); provavelmente o segredo de cifra mudou."
                            + " Reconfigure a chave nas configuracoes.",
                    exception.getClass().getSimpleName());
            return "";
        }
    }

    private static byte[] sha256(String source) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 indisponivel nesta JVM", exception);
        }
    }
}
