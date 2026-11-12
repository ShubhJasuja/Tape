package in.practice.tape;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Tape {
    public static void main(String[] args) {
        SpringApplication.run(Tape.class, args);
    }
}
