#!/usr/bin/env node
/*
 * nxinfo — the noxs-pkg Noxs environment info utility (Node.js example).
 * Prints basic, non-identifying environment information; pairs with the
 * @noxs/nx-api system.info module when loaded inside a package context.
 */
"use strict";

const os = require("os");

function main() {
    const rows = [
        ["Noxs package", "nxinfo"],
        ["Version", require("../package.json").version],
        ["Node", process.version],
        ["Platform", process.platform + "/" + process.arch],
        ["Hostname", os.hostname()],
        ["CPUs", String(os.cpus().length)],
        ["Free memory", Math.round(os.freemem() / 1024) + " KiB"],
    ];
    const width = Math.max(...rows.map(([key]) => key.length));
    for (const [key, value] of rows) {
        console.log(key.padEnd(width, " ") + " : " + value);
    }
    if (typeof globalThis.nx !== "undefined" && globalThis.nx.system) {
        // Inside a Noxs package UI the SDK is available (permission-gated).
        console.log("Noxs API : available");
    }
}

main();
