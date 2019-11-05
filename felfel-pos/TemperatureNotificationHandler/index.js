// Slack webhook integration helper
const { IncomingWebhook } = require('@slack/webhook');
// Webhook configured at apgiorgi#felfel
const slackWebhookUrl = process.env.SLACK_FELFEL_WEBHOOK_URL;
const webhook = new IncomingWebhook(slackWebhookUrl);

// Azure Function handler
module.exports = async function(context, mySbMsg) {
    // Anti-spoofing deviceId - https://docs.microsoft.com/en-us/azure/iot-hub/iot-hub-devguide-messages-construct#anti-spoofing-properties 
    const deviceId = context.bindingData.userProperties["iothub-connection-device-id"]
    const tempAvg = mySbMsg.tempAvg;
    
    // Send message to Slack channel via Webhook
    (async () => {
        await webhook.send({
          text: `${tempAvg>=15?'ALERT':'NOTIFICATION'}: The PoS "${deviceId}" is reporting a temperature of ${tempAvg}˚C`,
        });
    })();
};