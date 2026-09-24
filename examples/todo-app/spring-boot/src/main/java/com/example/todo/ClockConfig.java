package com.example.todo;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** 現在時刻の取得元。テストで差し替えられるように Bean にしておく。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
