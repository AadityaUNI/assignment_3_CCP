/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.beans;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OpenAIConfig.readKey: how a key.txt file's contents become the API key. The keys here are fake. */
class OpenAIConfigTests {

    @Test
    void plainKeyWithTrailingNewlineIsTrimmed() {
        assertEquals("fake-key-123", OpenAIConfig.readKey("fake-key-123\r\n"));
    }

    // this one is done with AI
    @Test
    void byteOrderMarkFromNotepadIsRemoved() {
        assertEquals("fake-key-123", OpenAIConfig.readKey("﻿fake-key-123\n"));
    }

    @Test
    void surroundingBlankLinesAndSpacesAreTrimmed() {
        assertEquals("fake-key-123", OpenAIConfig.readKey("\n   \n  fake-key-123  \n\n"));
    }

    @Test
    void emptyFileFailsWithoutEchoingItsContents() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> OpenAIConfig.readKey(" \n﻿\n"));
        assertEquals("The API key file is empty.", e.getMessage());
    }
}
