""" How to run this script:

Install the following dependencies for Python 3.10+:

 ```
python3 -m venv .venv
source .venv/bin/activate
pip3 install ollama
 ```

Install [Ollama](https://ollama.com/) on your local machine from their website. Then run the script.
"""

import time
import json
import ollama

if __name__ == "__main__":

    

    #exit(0)
    ###
    # USER INPUT
    ###

    count = 0
    count_time = 0
    with open('./grammars/Input/oracle_dataset.json', 'r', encoding='utf-8') as file:
        data_list = json.load(file)
        extracted_data = []
        # Iterate over each JSON element in the list
        for data in data_list[9:13]: #1008

            try:
                method = data.get('oracle', None)

                task = f"""===== TASK =====
                I am generating a Java line of code. I want you check if the Java syntax of the snippet is completed or not.
                
                For example:
                    - Arrays.stream(probabilities).anyMatch(jdVar -> jdVar < 0); is true (completed)
                    - Arrays.stream(probabilities). is false (incomplete)
                  
                Moreover, do not reply alsways true, your answer should be false, also if the snippet do not end with a semicolon.
                
                The snippet you should analyze is:\n {method}"""

                grammar = """\n
                ===== FEW-SHOT EXAMPLES =====
                Few-shot examples are given below. Please use these examples to check the completeness of the Java line.
                    - Arrays.stream(probabilities).anyMatch(jdVar -> jdVar < 0); is true (completed)
                    - Arrays.stream(probabilities). is false (incomplete)
                    - s <= 0.0; is true (completed)
                    - s <= is false (incomplete)
                    - index < 0; is true (completed)
                    - index is false (incomplete)
                """

                output = """\n
                ===== OUTPUT =====
                Put your answer in a simple JSON file with only string values. In the answer add only a syntactically correct JSON file, nothing else before or after the json file. An expected output example is:
                {
                "correct": "false",
                "comment": "assertEquals expected 2 arguments but found 1"
                }
                """

                prompt = task  + grammar + output

                print("\nWaiting for response from LLama3...\n")
                #print(f"Prompt: {prompt}")

                # Compute time of the next function call
                time_init = time.time()

                response = ollama.chat(model='llama3.2', 
                                      # options={"temperature":0.1},
                                       messages=[
                                            {
                                                'role': 'user',
                                                'content': f'{prompt}',
                                            },

                ])['message']['content']

                end_time  = time.time() - time_init
                count_time += end_time
                #print(f"Time taken: {round(end_time, 2)} seconds")

                print(response)

                llm_data = json.loads(response)


                extracted_data.append({
                    'input': method,
                    'oracle_response': str(llm_data.get('correct')), 
                    #'error': llm_data.get('error'), 
                    'ground_truth': data.get('correct'),
                    'time_taken': round(end_time, 2)
                    })

                if str(llm_data.get('correct')) == str(data.get('correct')):
                    count += 1
                else:
                    print(str(response))

            except Exception as e:
                print(f"Error: {e}")
                extracted_data.append({
                    'input': method,
                    'oracle_response': "Error", 
                    'error': str(e), 
                    'ground_truth': data.get('correct'),
                    'time_taken': "Error"
                    })

    # Write the extracted data to the output JSON file
    with open('./grammars/Output/llama3_eval.json', 'w', encoding='utf-8') as outfile:
        json.dump(extracted_data, outfile, indent=4)

    print(f"Accuracy: {round(count/len(data_list[9:1008]), 2)}")
    print(f"Average time taken: {round(count_time/len(data_list[9:1008]), 2)} seconds")
