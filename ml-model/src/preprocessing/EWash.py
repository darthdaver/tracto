import os
import sys
import json
import csv
import logging
from transformers import AutoTokenizer

# Setup the logger
logger = logging.getLogger("E-WASH")
logging.basicConfig(
    filename=os.path.join(os.path.dirname(os.path.abspath(__file__)),"ewash-oracles.log"),
    level=logging.INFO,
    format="[%(levelname)s] | %(name)s] | %(message)s"
)

class EWash:
    """
    Implement the whole process to generate the input string for the model using the E-WASH approach, progressively
    integrating the information about the invoked methods, the test class, and the focal class, only if the whole
    information fits in the input length limit of the model.
    The approach supported are:
        - Cascade: the information is integrated in the order defined in the configuration file (e.g. given the order
        [test prefix, test class, focal class ], means that the information (signature, javadoc and body) of the methods
        of the test prefix is added first, then the information of the fields and methods of the test class, and finally
        the information of the fields and methods of the focal class).
        - Incremental: the information is integrated progressively adding, in order of preference (e.g. [test prefix,
        test class, focal class ]) and in order of information type (e.g. [signature, javadoc, body]), the current
        information type of each element (test prefix, test class, focal class), then the next information type of the
        test_prefix, the test class and the focal class, and so on.
    The class support the generation of the whole input string if the information progressively added with the selected
    approach fits in the input length limit of the model, otherwise the input string only the information added before the
    limit was exceeded.
    """
    def __init__(self, tp, tc, fc, tgt, config=None):
        """
        Initialize the E-wash class with the information about the test prefix, the test class, the focal class, and the
        target oracle to generate. The configuration file for the E-WASH approach can be provided as an argument, otherwise
        the default configuration file is loaded. The tokenizer of the model used to encode the input is initialized from
        the configuration file.
        :param tp: the test prefix to process
        :param tc: the test class where the test prefix is defined
        :param fc: the focal class corresponding to the test class
        :param tgt: the target oracle
        :param config: the configuration object
        """
        self.ewash_dict = self.initialize_ewash(tp, tc, fc, tgt)
        self.ewash_config = self.load_default_ewash_config() if not config else config
        self.tokenizer = AutoTokenizer.from_pretrained(self.ewash_config['tokenizer'])

    def load_default_ewash_config(self):
        """
        Load the default configuration file for the E-WASH approach.
        :return: the content of the default configuration file for the E-WASH approach as a dictionary
        """
        logger.info("Custom path to configuration file not provided. Loading default configuration file for the E-WASH approach.")
        # Default path to the configuration file for the E-WASH approach
        path = os.path.join(os.path.abspath(os.path.dirname(__file__)), '..', 'resources', 'e-wash_config.json')
        # Load the configuration file for the E-WASH approach
        with open(path, 'r') as config_file:
            ewash_config = json.load(config_file)
        return ewash_config

    def count_tokens(self, input_text):
        """
        Count the number of tokens in the input text encoding it with the model's tokenizer (considering the special tokens).
        :param input_text: the input text to encode
        :return: the number of tokens in the input text
        """
        # Tokenize the input text encoding it with the model's tokenizer (considering the special tokens)
        tokens = self.tokenizer.encode(input_text, add_special_tokens=True)
        # Return the number of tokens in the input text
        return len(tokens)

    @staticmethod
    def tuple_info_2_str(tuple):
        """
        Convert the tuple containing the information (javadoc, signature, body) about the method or field to a string.
        :param tuple: the tuple containing the information (javadoc, signature, body) about the method or field
        :return: the string containing the information about the method or field
        """
        return f"{tuple[0]}{tuple[1]}{tuple[2]}"

    @staticmethod
    def tuples_info_2_str(tuples):
        """
        Convert the list of tuples containing the information (javadoc, signature, body) about the methods or fields to a string.
        :param tuples: the list of tuples containing the information (javadoc, signature, body) about the methods or fields
        :return: the string containing the information about the methods or fields
        """
        return f"\n".join([EWash.tuple_info_2_str(t) for t in tuples if t[0] != "" or t[1] != "" or t[2] != ""])

    def update_tupled_elements(self, ewash_counter, info_type, original_elements, tupled_elements, max_length):
        """
        Update the list of tuples of the fields or methods with the given information type, adding the information to the
        input string progressively, only if the whole information fits in the input length limit of the model. If the input
        length limit is exceeded, the methods don't stop the processing of the remaining elements that could not exceed
        the limit. The method returns a tuple containing the updated counter of the tokens in the input (after adding the
        new pieces of information, a boolean indicating if the input length limit was exceeded at least once during the
        update of the tuples, and a boolean indicating if at least one element was added to the input.
        :param ewash_counter: the counter of the tokens in the input up to the current point
        :param info_type: the type of information to add (signature, javadoc, body)
        :param original_elements: the list of the original elements (fields or methods) to process
        :param tupled_elements: the list containing the tuples of the fields/methods that are progressively enriched with
        :param max_length: the input length limit of the model
        :return: a tuple containing the updated counter of the tokens in the input, a boolean indicating if the input length
        limit was exceeded at least once during the update of the tuples, and a boolean indicating if at least one element
        was added to the input.
        """
        # Initialize flag to check if at least one elemtn was added to the input. It is important to avoid adding intro comment
        # if no element was added to the input
        element_added = False
        exceeded_limit = False
        # Iterate over the elements and add them sequentially to the input
        for i, el in enumerate(original_elements):
            info_counter  = self.count_tokens(el[info_type])
            # Check if the input length limit is not exceeded before adding the current field
            if (ewash_counter + info_counter) > max_length:
                exceeded_limit = True
                # Do not add the current field to the input, since the input length limit would be exceeded
                continue
            # Update the flag: at least one field was added to the input
            element_added = True
            tuple = tupled_elements[i]
            if info_type == 'javadoc':
                tuple = (f"{el[info_type]}\n", tuple[1], tuple[2])
            elif info_type == 'signature' or info_type == 'fullSignature':
                tuple = (tuple[0], f"{el[info_type]}\n", tuple[2])
            elif info_type == 'body':
                tuple = (tuple[0], tuple[1], f"{tuple[info_type]}\n")
            tupled_elements[i] = tuple
            # Update the counter of the tokens in the input
            ewash_counter += info_counter
        return (ewash_counter, exceeded_limit, element_added)

    @staticmethod
    def normalize_section(section):
        """
        Normalize the section and its attributes to conform to standard of the e-wash dictionary and conguration file.
        :param section: the section to process (test prefix, test class, or focal class)
        :return: the normalized section with the attributes in the expected format to be processed by the E-WASH approach
        """
        normalized_section = {}
        for s_type in section.keys():
            if s_type == 'fields':
                if "fullSignature" in section[s_type]:
                    # Normalize the label "fullSignature" to "fields" for the fields in the section
                    normalized_section[s_type] = [{"signature": f["fullSignature"]} for f in section[s_type]]
                elif "fields" in section[s_type]:
                    # If already normalized, return the fields
                    normalized_section[s_type] = section[s_type]
                else:
                    # Raise an error if the labels in the section are unexpected
                    raise ValueError(
                        f"Unexpected labels ({', '.join(section[s_type])}) in the section {section} for the type {s_type}")
            elif s_type == 'methods':
                if "invokedMethods" in section:
                    # Return the invoked methods in the section
                    normalized_section[s_type] = section['invokedMethods']
                elif "auxiliaryMethods" in section and "setupTearDownMethods" in section:
                    # Concatenate the auxiliary methods and the setup/teardown methods and return them
                    normalized_section[s_type] = section[s_type]['auxiliaryMethods'] + section[s_type][
                        'setupTearDownMethods']
                elif "constructors" in section and "methods" in section:
                    # Concatenate the constructors and the methods and return them
                    normalized_section[s_type] = section['constructors'] + section['methods']
                else:
                    # Raise an error if the labels in the section are unexpected
                    raise ValueError(
                        f"Unexpected labels ({', '.join(section.keys())}) in the section {section} for the type {s_type}")
            elif s_type in ['identifier', 'signature', 'javadoc', 'body']:
                # Return the body of the section
                normalized_section[s_type] = section[s_type]
        return normalized_section

    def initialize_ewash(self, tp, tc, fc, tgt):
        """
        Initialize the dictionary with the structured information of the datapoint to assemble the model input of the datapoint
        :param tp: the test prefix to process
        :param tc: the test class where the test prefix is defined
        :param fc: the focal class corresponding to the test class
        :param tgt: the target oracle
        :return: a dictionary containing the structured information of the datapoint, divided per test prefix, test class,
        and focal class. The dictionary contains the original information of the test prefix, test class, and focal class,
        the intro comments for the sections, and initializes the tuples of the fields and methods that will be progressively
        enriched with signatures, javadocs, and bodies. It also initializes the intro comments for each section of the input
        with flag False (the comments will be added only if at least a piece of information of one element in the section
        will be added to the final input). FInally, the dictionary contains the base input string (always present in the input).
        """
        # Initialize ewash dictionary
        ewash_dict = {}
        # Intro comments
        intros = {
            "test_prefix": {
                "methods": {
                    "label": f"// Invoked methods in test prefix\n",
                    "flag": False
                }
            },
            "test_class": {
                "fields": {
                    "label": f"// Fields defined in test class\n",
                    "flag": False
                },
                "methods": {
                    "label": f"// Methods defined in test class\n",
                    "flag": False
                }
            },
            "focal_class": {
                "fields": {
                    "label": f"// Fields defined in focal class\n",
                    "flag": False
                },
                "methods": {
                    "label": f"// Constructors and methods defined in focal class\n",
                    "flag": False
                }
            }
        }

        # Genetrate the base input string (always present in the input)
        # Add the test prefix to the input (src) (removing the target oracle to produce from the body)
        base_src = "// Test prefix\n"
        # Find the index of the target oracle in the test prefix
        assertion_idx = tp['body'].rfind(tgt)
        if assertion_idx == -1:
            tp['body'] = tp['body'][0:assertion_idx] + tp['body'][assertion_idx + len(tgt):]
        else:
            logger.error(f"Target oracle not found in the test prefix {tp['identifier']} of {tc['identifier']}: {tgt}")
        base_src += f"{tp['body']}\n\n"
        # Add the oracle mask the model has to generate
        base_src += "// Assertion\n"
        base_src += f"{self.ewash_config['mask_token']}\n\n"
        # Store the base input string in the ewash dictionary
        ewash_dict['base_src'] = base_src
        # Iterate over the sections of the input (test prefix, test class, focal class)
        for section_id in self.ewash_config['order']:
            # Get the information about the current section to process (test prefix, test class, or focal class)
            current_section = tp if section_id == 'test_prefix' else tc if section_id == 'test_class' else fc
            # Store the original section in the ewash dictionary
            ewash_dict[section_id]['original'] = current_section
            # Set the intro comments for the current section
            ewash_dict[section_id] = {"intros": intros[section_id]}
            # Initialize the list containing the tuples of the fields/methods that are progressively enriched with
            # signatures, javadocs and bodies. The first element of the tuple is the javadoc, the second is the
            # signature, and the third is the body (the order reflects the order of the information in a java class)
            ewash_dict[section_id]['tuples'] = {
                t: [("", "", "") for _ in current_section[t]] for t in self.ewash_config[section_id].keys()
            }
        # Return the initialized ewash dictionary
        return ewash_dict

    def run(self):
        """
        Run the E-WASH approach to generate the input string for the model. The methods collect the information and than
        generate the input string for the model using the E-WASH approach. The method returns a tuple containing the input
        string generated, the flag indicating if the input length limit was exceeded at least once, and the number of
        tokens in the final input.
        :return: a tuple containing the input string generated, the flag indicating if the input length limit was exceeded,
        and the number of tokens in the input string
        """
        # Collect e-wash information
        exceeded_limit = self.collect_ewash_info( )
        # Generate the input string for the model using the E-WASH approach
        ewash_str = self.generate_input()
        # Returns a tuple containing the input string generated, the flag indicating if the input length limit was exceeded,
        # and the number of tokens in the input string
        return ewash_str, exceeded_limit, self.count_tokens(self.tokenizer, ewash_str)

    def generate_input(self):
        """
        Generate the input string for the model using the E-WASH approach, assembling the base input with the information
        collected. The method returns the input string with the information progressively added according to the approach
        used (cascade or incremental).
        :return: the input string for the model using the E-WASH strategy selected (cascade or incremental)
        """
        # Initialize the input string with the input already provided (test prefix and assertion mask)
        ewash_str = self.base_src
        # Set the outer and inner loop to process the sections and types according to the approach used (cascade or incremental)
        outer_loop = self.ewash_config['sections'] if self.ewash_config['strategy'] == 'cascade' else self.ewash_config['types']
        inner_loop = self.ewash_config['types'] if self.ewash_config['strategy'] == 'cascade' else self.ewash_config['sections']
        for o_id in outer_loop:
            # Iterate over the elements of the current type (fields or methods)
            for i_id in inner_loop:
                section = self.ewash_dict[o_id] if self.ewash_config['strategy'] == 'cascade' else self.ewash_dict[i_id]
                s_type = i_id if self.ewash_config['strategy'] == 'cascade' else o_id
                if s_type in section:
                    if section['intros'][s_type]['flag']:
                        ewash_str += section['intros'][s_type]['label']
                        ewash_str += self.tuples_info_2_str(section['tuples'][s_type])
        logger.info(f"E-wash string generated.")
        return ewash_str

    def collect_ewash_info(self):
        """
        Collect all the information to generate the input for the model using the E-WASH approach, progressively integrating
        the information, according to the strategy defined in the configuration file (cascade or incremental). The method
        simulates the generation of the input string integrating the information until the input length limit of the model is
        exceeded or all the information is added to the input string. The method returns a boolean indicating if the input
        length limit was exceeded during the collection of the information.
        :return: A boolean indicating if the input length limit was exceeded during the collection of the information and
        the simulation of the input string with the information progressively added.
        """
        # Count the number of tokens in the input string at the beginning
        ewash_counter = self.count_tokens(self.tokenizer, self.base_src)
        # Check if the input length limit is not exceeded before adding any information
        if ewash_counter > self.ewash_config['max_length']:
            logger.info(f"The base input exceeds the input length limit.")
            return True
        # Get input max length
        max_length = self.ewash_config['max_length']
        # Initialize flag to check if the input length limit was exceeded
        exceeded_limit = False
        # Set the outer and inner loop to process the sections and types according to the approach used (cascade or incremental)
        outer_loop = self.ewash_config['sections'] if self.ewash_config['strategy'] == 'cascade' else self.ewash_config['types']
        inner_loop = self.ewash_config['types'] if self.ewash_config['strategy'] == 'cascade' else self.ewash_config['sections']
        # Iterate over the outer loop. If the strategy used is cascade the outer loop contains the sections, in order of
        # precedence (e.g. test prefix, the test class, and the focal class). Otherwise, if the strategy used is incremental
        # the outer loop contains the types to process (fields or methods) for each section, in order of preference.
        for o_id in outer_loop:
            # Iterate over the elements of the current type (fields or methods)
            for i_id in inner_loop:
                section_id = o_id if self.ewash_config['strategy'] == 'cascade' else i_id
                section = self.ewash_dict[o_id] if self.ewash_config['strategy'] == 'cascade' else self.ewash_dict[i_id]
                s_type = i_id if self.ewash_config['strategy'] == 'cascade' else o_id
                if s_type in section:
                    # Get the original elements (fields or methods) and the tuples of the fields or methods
                    original_elements = section['original'][s_type]
                    tupled_elements = section['tuples'][s_type]
                    # Get the intro comment for the current section type
                    intro_str = section['intros'][s_type]['label']
                    # Check if the intro comment is already added to the input string
                    intro_flag = section['intros'][s_type]['flag']
                    intro_counter = self.count_tokens(self.tokenizer, intro_str)
                    # Check if the input length limit is not exceeded before adding the elements of the current type
                    if not intro_flag and ((ewash_counter + intro_counter) > self.ewash_config['max_length']):
                        logger.info(f"The intro comment for the {section_id} {s_type}s exceeds the input length limit.")
                        continue
                    # Check if for the current type, the section has elements to add
                    if len(section[s_type]) == 0:
                        logger.info(f"No {s_type}s to add for the {section_id}")
                        continue
                    # Iterate over the different part of the information to add (signature, javadoc, body), in the order defined
                    # in the configuration file
                    for idx_info, info_type in self.ewash_config[section_id][s_type]:
                        logger.info(f"Adding {section_id} {s_type} {info_type}s to the input")
                        # Update the tuples of the fields or methods with the given information type
                        updated_ewash_counter, exceeded_limit, element_added = self.update_tupled_elements(
                            ewash_counter + intro_counter if not intro_flag else ewash_counter,
                            info_type,
                            original_elements,
                            tupled_elements,
                            max_length
                        )
                        if element_added:
                            intro_flag = True
                            # Update the counter of the tokens in the input
                            ewash_counter = updated_ewash_counter
                        # The update of the tuples was successful (the input length limit was not exceeded)
                        logger.info(f"Successfully added {section_id} fields {info_type}s to the input.")
        logger.info(f"E-wash processing completed.")
        return exceeded_limit
