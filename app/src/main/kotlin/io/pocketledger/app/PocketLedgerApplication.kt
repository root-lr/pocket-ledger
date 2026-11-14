package io.pocketledger.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["io.pocketledger"])
@EnableScheduling
@EnableAsync
class PocketLedgerApplication

fun main(args: Array<String>) {
    runApplication<PocketLedgerApplication>(*args)
}
