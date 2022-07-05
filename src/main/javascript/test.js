const { factory } = require("./index")

const input = `
<!--<html></html>-->
<html>
    <something></something>
    <head></head>
    <body><img
    src=""><a href="#yo">asd</a>
        <a href="#yo">asd</a>
        <a href="#yo">asd</a>
    </body>
</html>
`

const config = {
    rules: {
        "color-contrast": true,
        "valid-lang": false,
        "region": false,
        "not-a-rule": false
    },
    tags: []
}


factory.create().languagePlugin.onMessage(
    JSON.stringify({ seq: 1, arguments: { input, config } }),
    new class { write(msg) { console.log(JSON.stringify(JSON.parse(msg), null, 2)) }}
)
