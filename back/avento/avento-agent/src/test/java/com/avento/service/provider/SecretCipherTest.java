package com.avento.service.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A chave de API do provedor passou a viver no banco para sobreviver a um restart. Guardá-la em
 * texto puro trocaria um problema por outro: um dump, um backup ou um log de query entregaria
 * acesso pago à conta de quem usa o sistema.
 */
class SecretCipherTest {

    private SecretCipher cipher(String secret) {
        return new SecretCipher(secret, "jwt-irrelevante-aqui");
    }

    @Test
    void roundTripsTheSecret() {
        SecretCipher cipher = cipher("segredo-de-cifra");

        String encrypted = cipher.encrypt("AIzaSyD-chave-de-exemplo");

        assertThat(encrypted).doesNotContain("AIzaSyD-chave-de-exemplo");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("AIzaSyD-chave-de-exemplo");
    }

    // Nonce aleatorio: o mesmo texto nao pode gerar o mesmo cifrado, senao da para inferir repeticao.
    @Test
    void producesADifferentCiphertextEachTime() {
        SecretCipher cipher = cipher("segredo-de-cifra");

        assertThat(cipher.encrypt("mesma-chave")).isNotEqualTo(cipher.encrypt("mesma-chave"));
    }

    // Trocar o segredo torna o gravado ilegivel: precisa devolver vazio, nao explodir, para o
    // usuario apenas reconfigurar em vez de encontrar a aplicacao quebrada.
    @Test
    void returnsEmptyWhenTheEncryptionSecretChanged() {
        String encrypted = cipher("segredo-antigo").encrypt("minha-chave");

        assertThat(cipher("segredo-novo").decrypt(encrypted)).isEmpty();
    }

    // AES-GCM autentica: texto adulterado falha em vez de virar lixo silencioso.
    @Test
    void rejectsTamperedCiphertext() {
        SecretCipher cipher = cipher("segredo-de-cifra");
        String encrypted = cipher.encrypt("minha-chave");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThat(cipher.decrypt(tampered)).isEmpty();
    }

    // Valor gravado antes desta versao nao tem prefixo: tratar como ausente, nunca devolver texto puro.
    @Test
    void ignoresValuesStoredWithoutEncryption() {
        assertThat(cipher("qualquer").decrypt("AIzaSy-chave-em-texto-puro")).isEmpty();
    }

    @Test
    void handlesEmptyInput() {
        SecretCipher cipher = cipher("segredo");

        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.encrypt(null)).isEmpty();
        assertThat(cipher.decrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isEmpty();
    }

    // Sem segredo dedicado, deriva do JWT para funcionar sem configuracao extra.
    @Test
    void fallsBackToTheJwtSecret() {
        SecretCipher cipher = new SecretCipher("", "jwt-secret-do-projeto");

        assertThat(cipher.decrypt(cipher.encrypt("chave"))).isEqualTo("chave");
    }

    // O valor mascarado que a tela devolve nao pode ser regravado como chave.
    @Test
    void doesNotAcceptTheMaskedValueAsARealKey() {
        assertThat(ModelProviderService.isRealApiKey("AIza••••••••1234")).isFalse();
        assertThat(ModelProviderService.isRealApiKey("")).isFalse();
        assertThat(ModelProviderService.isRealApiKey(null)).isFalse();
        assertThat(ModelProviderService.isRealApiKey("AIzaSyD-chave-real")).isTrue();
    }
}
