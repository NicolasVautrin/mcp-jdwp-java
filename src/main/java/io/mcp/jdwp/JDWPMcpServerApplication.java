package io.mcp.jdwp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server for JDWP inspection using JDI
 *
 * Tools are automatically detected via @McpTool annotations in JDWPTools
 */
@SpringBootApplication
public class JDWPMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JDWPMcpServerApplication.class, args);
    }
}
