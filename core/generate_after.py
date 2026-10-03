from prompt_assembler import build_system_message

def get_output(msg):
    return build_system_message(msg)

with open("/data/data/com.termux/files/home/p1_after.txt", "w") as f:
    for msg in ["hi", "whats my phone?", "anime!!!"]:
        f.write(f"--- Input: {msg} ---\n")
        f.write(get_output(msg))
        f.write("\n\n")