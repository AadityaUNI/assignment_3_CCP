package comp3011.assignment1;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Own cache file, so running the tests never reads or rewrites the player's real cache/subtitle-cache.json.
@SpringBootTest(properties = "subtitle.cache.file=target/test-subtitle-cache.json")
class Assignment1ApplicationTests {

    @Test
    void contextLoads() {
    }

}
