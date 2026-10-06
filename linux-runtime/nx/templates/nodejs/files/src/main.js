#!/usr/bin/env node
'use strict';

function main(argv) {
    for (const arg of argv) {
        console.log(arg);
    }
    return 0;
}

if (require.main === module) {
    process.exitCode = main(process.argv.slice(2));
}

module.exports = { main };
