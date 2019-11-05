// Load Twilio client with auth data from Key Vault
const twilioAccountSid = process.env.TWILIO_ACCOUNT_SID;
const twilioAuthToken = process.env.TWILIO_AUTH_TOKEN;
const twilioClient = require('twilio')(twilioAccountSid, twilioAuthToken);

// Azure Function handler
module.exports = async function(context, mySbMsg) {
    // Anti-spoofing deviceId - https://docs.microsoft.com/en-us/azure/iot-hub/iot-hub-devguide-messages-construct#anti-spoofing-properties 
    const deviceId = context.bindingData.userProperties["iothub-connection-device-id"]
    const tempAvg = mySbMsg.tempAvg;

    // ALERT SMS receiving numbers
    const toPhoneNumbers = ['+41767023981'];

    if (tempAvg >= 15) {
      // Send ALERT SMS via Twilio
      // ! Twilio doesn't support bulk send.
      toPhoneNumbers.forEach(toPhoneNumber => {
        (async () => {
          await twilioClient.messages.create({
              body: `ALERT: The PoS "${deviceId}" is reporting a temperature of ${tempAvg}˚C`,
              from: 'FELFEL PoS',
              to: toPhoneNumber
          });
        })();  
      })      
    }
};