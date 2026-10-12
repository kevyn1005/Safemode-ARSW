package com.safemode.gateway.notify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChatIdsTest {

    @Test
    void sacaLosChatsDistintosEnElOrdenEnQueAparecen() {
        String json = """
                {"ok":true,"result":[
                  {"update_id":1,"message":{"message_id":1,"from":{"id":111},"chat":{"id":111,"first_name":"Ana","type":"private"},"text":"/start"}},
                  {"update_id":2,"message":{"chat":{"id":111,"first_name":"Ana","type":"private"},"text":"hola"}},
                  {"update_id":3,"message":{"chat":{"id":-1001234567890,"title":"Vigilancia","type":"supergroup"}}},
                  {"update_id":4,"my_chat_member":{"chat":{"id":5}}},
                  {"update_id":5,"channel_post":{"chat":{"id":-1009,"title":"Avisos","type":"channel"}}}
                ]}
                """;

        assertEquals(List.of(
                new TelegramChatIds.Chat(111, "private", "Ana"),
                new TelegramChatIds.Chat(-1001234567890L, "supergroup", "Vigilancia"),
                new TelegramChatIds.Chat(-1009, "channel", "Avisos")), TelegramChatIds.parse(json));
    }

    @Test
    void sinMensajesNoHayChats() {
        assertTrue(TelegramChatIds.parse("{\"ok\":true,\"result\":[]}").isEmpty());
        assertTrue(TelegramChatIds.parse("{\"ok\":true}").isEmpty());
    }
}
