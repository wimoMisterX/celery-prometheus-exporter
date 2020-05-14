
# Last Yard Event Forwarder

Subscribes to events and pushes the events out to a varirety of endpoints.

## Getting Started
1. Create RabbitMQ user and vhost. Allow `guest` RabbitMQ management user to access vhost.
	```
	sudo rabbitmqctl add_user evey evey
	sudo rabbitmqctl add_vhost evey
	sudo rabbitmqctl set_permissions --vhost evey guest ".*" ".*" ".*"
	```
2. Create a `config.json` file
	`echo "[]" > config.json`
3. Start event forwarder
	`lein run`

## Usage
The [environ](https://github.com/weavejester/environ) lirbary is used to manage the following environment variables. Please refer to the library's README.md to see the hierarchy of variables.

|Environment Variable  | Description | Default |
|--|--|--|
|PORT|Local port to listen on|6999|
|RABBITMQ_URL|RabbitMQ connection uri|amqp://evey:evey@localhost:5672/evey|
|EXCHANGE_NAME|Name of the RabbitMQ exchange to subscribe to|events|
|CONFIG_FILE|Path to event config file|./config.json|


## Configuration

The `config.json` is a list of forwarder configs as below:
```
[
    {
	# unique id for forwarader configuration
	"forwarder-id": "dev/events",

	# signiq8 instance which defines the forwarder
	"siq8-instance": "dev",

	# list of events topics to send to the endpoint
	"event-types": [
  	    "siq8.events.*"
        ],

	# endpoint configuration to which events are sent to
	# the "type" determines the rest of the keys in the endpoint block
    "endpoint": {
	    "type": "api",
            "url": "http://localhost:8000/events",
            "headers": {
	        "Authorization": "Token 3bc98d6e01084d35a3705354946f2e04bf4224ab"
            }
        },
	
	# number of events to batch in one send
    "batch-size": 2,

	# number of milliseconds to wait for a batch of events to be formed
    "batch-timeout": 2500,

	# the maximum throughtput of sends per second
    "max-rate": 10
    },
    {
	 ...
    }
]
```

## Endpoint support
### api
Post to an authenticated HTTP REST API
* The  `headers` map would be merged into the request headers.
* `basic-auth` could be passed in if basic HTTP authentication is to be used, otherwise specify the `Authorization` header in the `headers` map.
```
{
    "type": "api",
    "url": "http://localhost:8000/events",
    "headers": {
        "Authorization": "Token 3bc98d6e01084d35a3705354946f2e04bf4224ab",
        "X-Version": 1
    },
    "basic-auth": ["username", "password"]
}
```
