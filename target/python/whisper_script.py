import argparse
import sys
import time
import os
import whisper
import torch
import gc
import threading
import json

def print_progress(message, status='info'):
    """Print progress message to stderr so Java can capture it"""
    # Format as JSON for structured parsing in Java
    data = {
        'message': message,
        'status': status,
        'timestamp': time.time()
    }
    print(json.dumps(data), file=sys.stderr, flush=True)
    
def progress_updater(start_time, stop_event):
    """Thread that periodically updates on the progress"""
    dots = 0
    while not stop_event.is_set():
        elapsed = time.time() - start_time
        # Create a loading animation with dots
        dots = (dots + 1) % 4
        dot_str = '.' * dots
        print_progress(f"Transcribing{dot_str.ljust(3)} ({elapsed:.1f}s elapsed)", "working")
        time.sleep(1.0)  # Update every second

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Transcribe an audio file using Whisper.')
    parser.add_argument('audio_file', type=str, help='Path to the audio file to transcribe.')
    parser.add_argument('--model', type=str, default='base', choices=['tiny', 'base', 'small', 'medium', 'large'], 
                        help='Whisper model to use (tiny, base, small, medium, large)')
    parser.add_argument('--language', type=str, default=None, help='Language code (optional, auto-detected if not specified)')
    parser.add_argument('--device', type=str, default='cpu', choices=['cpu', 'cuda'], help='Device to use for inference')

    args = parser.parse_args()

    # Check if the file exists
    if not os.path.exists(args.audio_file):
        print_progress(f"Error: Audio file not found at {args.audio_file}")
        sys.exit(1)
    
    # Check for available CUDA for better performance if requested
    if args.device == 'cuda' and not torch.cuda.is_available():
        print_progress("Warning: CUDA requested but not available. Falling back to CPU.")
        args.device = 'cpu'
    
    try:
        print_progress(f"Loading Whisper model: {args.model}...", "loading")
        
        # Load the model (will download if not present)
        model = whisper.load_model(args.model, device=args.device)
        print_progress(f"Model {args.model} loaded successfully.", "ready")
        
        print_progress(f"Starting transcription for: {os.path.basename(args.audio_file)}", "processing")
        start_time = time.time()
        
        # Start a progress update thread
        stop_thread = threading.Event()
        progress_thread = threading.Thread(
            target=progress_updater,
            args=(start_time, stop_thread),
            daemon=True
        )
        progress_thread.start()
        
        # Perform transcription
        result = model.transcribe(
            args.audio_file, 
            fp16=False if args.device == 'cpu' else True,  # fp16=False for wider CPU compatibility
            language=args.language,
            verbose=True  # Show progress in terminal
        )
        
        # Stop the progress update thread
        stop_thread.set()
        progress_thread.join(timeout=1.0)
        
        end_time = time.time()
        processing_time = end_time - start_time
        print_progress(f"Transcription complete in {processing_time:.2f} seconds.", "complete")
        
        # Clean up to reduce memory usage
        del model
        gc.collect()
        if args.device == 'cuda':
            torch.cuda.empty_cache()
        
        # Print a JSON completion message to stdout for Java to capture
        print(json.dumps({
            "message": "Transcription complete",
            "status": "complete",
            "timestamp": time.time()
        }))
        print(result["text"].strip())
        
        sys.exit(0)  # Exit with 0 for success

    except Exception as e:
        error_message = str(e)
        print(json.dumps({
            "message": f"Error during transcription: {error_message}",
            "status": "error",
            "timestamp": time.time()
        }))
        sys.exit(1)  # Exit with a non-zero code to indicate failure