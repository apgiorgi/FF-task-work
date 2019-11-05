// Configure IoT Hub service SDK
const iothub = require('azure-iothub');
const iotConnectionString = process.env.IOT_HUB_REGISTRY_CONN_STRING;
const iotHubId = /HostName\=(.*)\.azure/gi.exec(iotConnectionString)[1]; // Get the IoT Hub ID from the connection string
const registry = iothub.Registry.fromConnectionString(iotConnectionString);

// Helper function to generate a Connection String from a device info object
function generateConnectionString(deviceInfo, hub){
    return `HostName=${hub}.azure-devices.net;DeviceId=${deviceInfo.deviceId};SharedAccessKey=${deviceInfo.authentication.symmetricKey.primaryKey}`;
}

let deviceInfo;
let connectionString;

module.exports = async function (context, req) {
    // Device object
    var device = {
        deviceId: 'felfel-' + Date.now(),
        status: 'disabled',
        tags: {
            "companyName": "FELFEL AG",
            "address": "Zurichstrasse 1"
        }
    };
    
    // Create the IoT Device
    try {
        // Promise returns the full http response object
        result = await registry.create(device);
        deviceInfo = result.responseBody;
    } catch (error) {
        console.log('error: ' + error.toString());
    }

    if (deviceInfo) {
        connectionString = generateConnectionString(deviceInfo, iotHubId);
    }

    if (connectionString) {
        context.res = {
            // status: 200, /* Defaults to 200 */
            body: connectionString
        };    
    } else {
        context.res = {
            status: 400,
            body: "Nothing for you here!"
        };    
    }
};