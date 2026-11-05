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

@Configuration
public class OpenAIConfig {

    // openai.key-file is set by the player's --keyfile option. Without it, the key comes from OPENAI_API_KEY.
    @Bean
    public OpenAIClient openAIClient(@Value("${openai.key-file:}") String keyFile) throws IOException {
        if (keyFile.isBlank()) {
            return OpenAIOkHttpClient.fromEnv();
        }
        // The key is only held in memory by the client: never logged, printed or copied to another file.
        String apiKey = Files.readString(Path.of(keyFile)).strip();
        return OpenAIOkHttpClient.builder().apiKey(apiKey).build();
    }
}
