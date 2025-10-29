#!/usr/bin/env python3
"""Inspection detaillee des variables locales"""

import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_variables():
    server_params = StdioServerParameters(
        command="java",
        args=[
            "-jar",
            "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
        ]
    )

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            # Connexion
            await session.call_tool("jdwp_connect", {
                "host": "localhost",
                "port": 55005
            })

            # Thread 15 = http-nio-8080-exec-1 avec le breakpoint
            thread_id = 15

            # Inspecter les 10 premiers frames
            for frame_idx in range(10):
                print(f"\n{'='*60}")
                print(f"FRAME {frame_idx}")
                print('='*60)

                result = await session.call_tool("jdwp_get_locals", {
                    "threadId": thread_id,
                    "frameIndex": frame_idx
                })

                locals_text = result.content[0].text

                if "Error:" in locals_text:
                    print(f"(pas de variables disponibles)")
                else:
                    print(locals_text)

            # Deconnexion
            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_variables())
