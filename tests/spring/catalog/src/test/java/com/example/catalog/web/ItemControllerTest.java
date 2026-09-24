package com.example.catalog.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.catalog.domain.Category;
import com.example.catalog.service.ItemService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(LocalDateTime.of(2026, 1, 2, 3, 4).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ItemService itemService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM items WHERE price < ?", 0);
    }

    @Test
    void listShowsItems() throws Exception {
        jdbcTemplate.update("INSERT INTO items (name, category, price) VALUES (?, ?, ?)", "Pen", "TOOL", 120);
        mockMvc.perform(get("/items").param("q", "pe"))
                .andExpect(status().isOk())
                .andExpect(view().name("items/list"))
                .andExpect(content().string(containsString("Pen")))
                .andExpect(content().string(not(containsString("Book"))));
        assertEquals(1, itemService.search("pe", List.of()).size());
    }

    @Test
    void createRedirects() throws Exception {
        mockMvc.perform(post("/items").param("name", "Rice").param("category", "FOOD").param("price", "500"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items"))
                .andExpect(flash().attribute("message", "added"));
        mockMvc.perform(post("/items/{id}/discount", 999_999)).andExpect(status().isNotFound());
        assertTrue(!itemService.search(null, List.of(Category.FOOD)).isEmpty());
    }
}
