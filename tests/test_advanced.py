#!/usr/bin/env python3
"""Test avance pour MCP JDWP server - threads et variables"""

import asyncio
import json
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_advanced():
    server_params = StdioServerParameters(
        command="java",
        args=[
            "-jar",
            "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
        ]
    )

    print("Demarrage du serveur MCP JDWP...")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            print("OK - Serveur initialise!\n")

            # Connexion JDWP
            print("Connexion JDWP (localhost:55005)...")
            result = await session.call_tool("jdwp_connect", {
                "host": "localhost",
                "port": 55005
            })
            print(f"{result.content[0].text}\n")

            # Liste des threads
            print("=== LISTE DES THREADS ===")
            result = await session.call_tool("jdwp_get_threads", {})
            threads_text = result.content[0].text
            print(threads_text)

            # Parser pour trouver les threads suspendus
            suspended_threads = []
            lines = threads_text.split('\n')
            current_thread_id = None
            for line in lines:
                if line.startswith('  ID: '):
                    current_thread_id = int(line.split(': ')[1])
                if line.startswith('  Suspended: true'):
                    suspended_threads.append(current_thread_id)

            print(f"\nThreads suspendus trouves: {len(suspended_threads)}")

            if suspended_threads:
                # Tester la stack pour un thread suspendu
                thread_id = suspended_threads[0]
                print(f"\n=== STACK DU THREAD {thread_id} ===")
                result = await session.call_tool("jdwp_get_stack", {
                    "threadId": thread_id
                })
                stack_text = result.content[0].text
                print(stack_text)

                # Tester les variables locales du frame 0
                print(f"\n=== VARIABLES LOCALES (FRAME 0) ===")
                result = await session.call_tool("jdwp_get_locals", {
                    "threadId": thread_id,
                    "frameIndex": 0
                })
                print(result.content[0].text)
            else:
                print("\nAucun thread suspendu - il faut mettre un breakpoint dans IntelliJ")
                print("Pour tester les variables locales:")
                print("1. Ouvre IntelliJ")
                print("2. Mets un breakpoint dans le code Tomcat")
                print("3. Fais une requete pour declencher le breakpoint")
                print("4. Relance ce script")

            # Deconnexion
            print("\n=== DECONNEXION ===")
            result = await session.call_tool("jdwp_disconnect", {})
            print(result.content[0].text)

if __name__ == "__main__":
    asyncio.run(test_advanced())
