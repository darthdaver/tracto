import json
import os
import sys
from dotenv import load_dotenv
import anthropic
from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
from anthropic.types.messages.batch_create_params import Request

MESSAGES_LIMITS = 100000

load_dotenv()
# Check for API key in environment variables
api_key = os.getenv("ANTHROPIC_API_KEY")
if not api_key:
    print("ERROR: ANTHROPIC_API_KEY environment variable not set.")
    print("Please set it with: export ANTHROPIC_API_KEY='your_api_key_here'")
    sys.exit(1)
# Initialize Anthropic client
try:
    client = anthropic.Anthropic(api_key=api_key)
except Exception as e:
    print(f"ERROR: Failed to initialize Anthropic client: {e}")
    sys.exit(1)

if __name__ == '__main__':
    batches_dir_path = sys.argv[1]
    batches = []
    requests = []
    msg_counter = 0

    for root, dirs, files in os.walk(batches_dir_path):
        for file in files:
            with open(os.path.join(root, file), "r") as batch_file:
                batch = json.load(batch_file)
                for r in batch:
                    r_msg_count = len(r["params"]["messages"])

                    if r_msg_count > MESSAGES_LIMITS:
                        print(f"The request contains too many messages ({r_msg_count}). The limit is: {MESSAGES_LIMITS}. Request skipped.")
                        continue
                    if (msg_counter + r_msg_count) < MESSAGES_LIMITS:
                        msg_counter += r_msg_count
                        requests.append(Request(
                            custom_id = r["custom_id"],
                            params = MessageCreateParamsNonStreaming(r["params"])
                        ))
                    else:
                        batches.append(requests)
                        requests = []
                        msg_counter = r_msg_count
                        requests.append(Request(
                            custom_id=r["id"],
                            params=MessageCreateParamsNonStreaming(r["params"])
                        ))
    batches.append(requests)
    for b in batches:
        msg_batch = client.messages.batches.create(
            requests=b
        )
        print(msg_batch)