/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.beans;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class OpenAIConfig {
    // 2 tries x 25 s ends before the player resends at 60 s, so a hung call can't finish later and bill twice.
    static final Duration WHISPER_TIMEOUT = Duration.ofSeconds(25);
    static final int WHISPER_MAX_RETRIES = 1;

    // openai.key-file is set by the player's --keyfile option. Without it, the key comes from OPENAI_API_KEY.
    @Bean
    public OpenAIClient openAIClient(@Value("${openai.key-file:}") String keyFile) throws IOException {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .timeout(WHISPER_TIMEOUT)
                .maxRetries(WHISPER_MAX_RETRIES);
        if (keyFile.isBlank()) {
            return builder.fromEnv().build();
        }
        // The key is only held in memory by the client
        return builder.apiKey(readKey(Files.readString(Path.of(keyFile)))).build();
    }

    // Key = file contents trimmed, minus any Notepad BOM.
    static String readKey(String fileContents) {
        String key = fileContents.replace("﻿", "").strip(); // strip() keeps a BOM: it isn't whitespace
        if (key.isEmpty()) {
            throw new IllegalStateException("The API key file is empty.");
        }
        return key;
    }
}
