const { execFile } = require('node:child_process');

function openBrowser(url) {
    const environment = { ...process.env };
    delete environment.BROWSER;
    return new Promise((resolve, reject) => {
        execFile(process.platform === 'darwin' ? 'open' : 'xdg-open', [url], {
            env: environment, timeout: 15000,
        }, error => {
            if (error) reject(new Error('Cannot open the default browser. Check your desktop session.'));
            else resolve();
        });
    });
}

module.exports = openBrowser;
if (require.main === module) {
    openBrowser(process.argv[2]).catch(error => {
        console.error(error.message);
        process.exitCode = 1;
    });
}
