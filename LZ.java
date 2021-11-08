import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

class Match {
    public static final int SERIALIZED_BYTES = Short.BYTES * 2;

    private final int distance;
    private final int length;

    public Match(int distance, int length) {
        this.distance = distance;
        this.length = length;
    }

    public int getDistance() {
        return distance;
    }

    public int getLength() {
        return length;
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream(SERIALIZED_BYTES);
            DataOutputStream outputStream = new DataOutputStream(byteOutputStream);
            outputStream.writeShort(distance);
            outputStream.writeShort(length);
            return byteOutputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Match deserialize(byte[] data) {
        try {
            ByteArrayInputStream byteOutputStream = new ByteArrayInputStream(data);
            DataInputStream inputStream = new DataInputStream(byteOutputStream);
            int distance = inputStream.readShort();
            int length = inputStream.readShort();
            return new Match(distance, length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

class ArrayWithSize {
    public final byte[] array;
    public final int size;

    public ArrayWithSize(byte[] array, int size) {
        this.array = array;
        this.size = size;
    }
}

class CompositeBuffer {
    private final int bufferCount;
    private final ArrayWithSize[] buffers;

    public CompositeBuffer(int bufferCount) {
        this.bufferCount = bufferCount;
        buffers = new ArrayWithSize[bufferCount];
        for (int i = 0; i < bufferCount; i++) {
            buffers[i] = new ArrayWithSize(new byte[0], 0);
        }
    }

    public int push(ArrayWithSize buffer) {
        int moveBackOffset = -buffers[0].size;
        for (int i = 1; i < buffers.length; i++) {
            buffers[i - 1] = buffers[i];
        }
        buffers[buffers.length - 1] = buffer;
        return moveBackOffset;
    }

    public int length() {
        int sum = 0;
        for (int i = 0; i < buffers.length; i++) {
            sum += buffers[i].size;
        }
        return sum;
    }

    public byte get(int index) {
        int bufferIndex = -1;
        int relativeIndex = index;

        for (int i = 0; i < buffers.length && bufferIndex == -1; i++) {
            if (relativeIndex < buffers[i].size) {
                bufferIndex = i;
            }
        }
        if (bufferIndex == -1) {
            throw new IndexOutOfBoundsException();
        }

        return buffers[bufferIndex].array[relativeIndex];
    }
}

class OutputWriter {
    public static final int MAX_ENTRIES = 127;
    public static final int BYTE_ENTRY_SIZE = 1;
    public static final int MATCH_ENTRY_SIZE = Match.SERIALIZED_BYTES;

    private final int outputChunkSize;
    private byte[] finalizedOutput; // Only returned when output is full or getfinalblock is called

    private boolean isCompressed = false;
    private byte[] outputChunk; // The array we write to and fill. Refresh when filled up
    private byte[] blockStartArray;
    private int blockStartIndex;
    private int entryIndex;
    private int entryCount;

    public OutputWriter(int outputChunkSize) {
        this.outputChunkSize = outputChunkSize;
        this.outputChunk = new byte[outputChunkSize];

        blockStartArray = outputChunk;
        blockStartIndex = 0;
        entryIndex = blockStartIndex + 1;
        entryCount = 0;
    }

    // First byte: Amount of entries in block, negative = uncompressed, positive =
    // compressed
    // Following bytes: Entries
    // MUST be possible to write blocks across output blocks

    // * We must write entries of the same type UNTIL a type switch occurs
    // (uncompressed -> compressed or other way around)
    // * When a type switch occurs, we must write the amount of entries into the
    // first byte
    // * When getFinalBlock is called, first byte must also be written, and the
    // output buffer must be returned.
    // * When the entry count reaches 127 (or -128), the first byte must also be
    // written.
    //
    // NOTE: The first byte can be in the previous buffer, and it must NOT be
    // returned until the first byte is written into it.

    private void finalizeEntryBlock() {
        if (entryCount == 0) {
            return;
        }

        int multiplier = isCompressed ? -1 : 1;
        blockStartArray[blockStartIndex] = (byte) (entryCount * multiplier);
        if (blockStartArray != outputChunk) {
            finalizedOutput = blockStartArray;
        }

        allocateNewChunkIfFull();
        blockStartIndex = entryIndex++;
        blockStartArray = outputChunk;
    }

    private void allocateNewChunkIfFull() {
        if (entryIndex < outputChunkSize) {
            return;
        }
        entryIndex = 0;
        outputChunk = new byte[outputChunkSize];
    }

    private void incrementEntryCount() {
        if (++entryCount == MAX_ENTRIES) {
            finalizeEntryBlock();
        }
    }

    private void setCompressed(boolean compressed) {
        if (isCompressed != compressed) {
            finalizeEntryBlock();
        }
        isCompressed = compressed;
    }

    public void writeMatch(Match match) {
        setCompressed(true);
        allocateNewChunkIfFull();

        byte[] serialized = match.serialize();
        for (int i = 0; i < serialized.length; i++) {
            outputChunk[entryIndex++] = serialized[i];
            if (i < serialized.length - 1) {
                allocateNewChunkIfFull();
            }
        }
        incrementEntryCount();
    }

    public void writeByte(byte b) {
        setCompressed(false);
        allocateNewChunkIfFull();

        outputChunk[entryIndex++] = b;
        incrementEntryCount();
    }

    public byte[] getFullChunk() {
        return finalizedOutput;
    }

    public ArrayWithSize getFinalChunk() {
        finalizeEntryBlock();
        byte[] finalChunk = outputChunk;
        outputChunk = null;
        return new ArrayWithSize(finalChunk, entryIndex);
    }
}

class Window {
    private final CompositeBuffer buffer;
    private final int windowSize;
    private final int minMatchLength;
    int offset = 0;
    int limit = 0;

    public Window(CompositeBuffer buffer, int windowSize, int minMatchLength) {
        this.buffer = buffer;
        this.windowSize = windowSize;
        this.minMatchLength = minMatchLength;
    }

    public void setLimit(int index) {
        limit = index;
    }

    public void slideBack(int bytes) {
        offset -= bytes;
    }

    public void slideForward(int bytes) {
        offset += bytes;
    }

    public Match findMatch(CompositeBuffer lookaheadBuffer, int offset, int length) {
        int matchIndex = -1;
        int matchLength = 0;
        for (int i = this.offset; i < this.limit; i++) {
            for (int j = 0; offset + j < length && i + j < this.limit; j++) {
                if (buffer.get(i + j) != lookaheadBuffer.get(offset + j)) {
                    if (j > matchLength) {
                        matchIndex = i;
                        matchLength = j;
                    }
                    break;
                }
            }
        }
        if (matchLength > minMatchLength) {
            return new Match(this.limit - matchIndex, matchLength);
        } else {
            return null;
        }
    }

    public byte getByte(int index) {
        if (index < offset || index >= limit) {
            throw new IndexOutOfBoundsException();
        }

        return buffer.get(index);
    }
}

class LempelZivAlgorithm {
    private static final int MIN_MATCH_LENGTH = Match.SERIALIZED_BYTES + 1; // 5bytes
    private static final int CHUNK_SIZE = 16384; // 16 KiB
    private static final int OUTPUT_CHUNK_SIZE = 16777220; // 16 MiB

    private final InputStream inputStream;
    private final OutputStream outputStream;

    public LempelZivAlgorithm(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    private static CompositeBuffer createPreFilledBuffer(InputStream stream) throws IOException {
        CompositeBuffer compositeBuffer = new CompositeBuffer(CHUNK_SIZE * 3);
        int chunksRead = 0;
        int bytesRead = 0;
        byte[] buffer = new byte[CHUNK_SIZE];
        while (chunksRead < 3 && (bytesRead = stream.read(buffer, 0, buffer.length)) != -1) {
            compositeBuffer.push(new ArrayWithSize(buffer, bytesRead));
            chunksRead++;
        }
        while (chunksRead < 3) {
            compositeBuffer.push(new ArrayWithSize(new byte[0], 0));
            chunksRead++;
        }
        return compositeBuffer;
    }

    private static int readChunkIntoCompositeBuffer(CompositeBuffer buffer, InputStream stream) throws IOException {
        byte[] array = new byte[CHUNK_SIZE];
        int bytesRead = stream.read(array, 0, array.length);
        if (bytesRead != -1) {
            return buffer.push(new ArrayWithSize(array, bytesRead));
        }
        return 0;
    }

    public void compress() throws IOException {
        CompositeBuffer buffer = createPreFilledBuffer(inputStream);
        Window window = new Window(buffer, CHUNK_SIZE, MIN_MATCH_LENGTH);
        OutputWriter outputWriter = new OutputWriter(OUTPUT_CHUNK_SIZE);

        // Write initial block
        int initialBlockBytes = Math.min(OutputWriter.MAX_ENTRIES * OutputWriter.BYTE_ENTRY_SIZE, buffer.length());
        for (int i = 0; i < initialBlockBytes; i++) {
            System.out.printf("%c", buffer.get(i));
            outputWriter.writeByte(buffer.get(i));
        }

        // fill read-buffer with first arrays
        for (int lookaheadIndex = initialBlockBytes; lookaheadIndex < buffer.length();) {
            if (lookaheadIndex >= 2 * CHUNK_SIZE) {
                int moveBack = readChunkIntoCompositeBuffer(buffer, inputStream);
                lookaheadIndex -= moveBack;
                window.slideBack(moveBack);
            }

            int remainingLookahead = Math.min(CHUNK_SIZE, buffer.length() - lookaheadIndex);
            window.setLimit(lookaheadIndex);
            Match match = window.findMatch(buffer, lookaheadIndex, lookaheadIndex + remainingLookahead);
            int moveForward = 0;
            if (match == null) {
                System.out.printf("%c", buffer.get(lookaheadIndex));
                outputWriter.writeByte(buffer.get(lookaheadIndex));
                moveForward = 1;
            } else {
                System.out.printf("(%d, %d)", match.getDistance(), match.getLength());
                moveForward = match.getLength();
                outputWriter.writeMatch(match);
            }

            byte[] output = outputWriter.getFullChunk();
            if (output != null) {
                outputStream.write(output);
            }

            window.slideForward(moveForward);
            lookaheadIndex += moveForward;
        }

        ArrayWithSize finalOutput = outputWriter.getFinalChunk();
        if (finalOutput != null) {
            outputStream.write(finalOutput.array, 0, finalOutput.size);
        }
    }
}

class XCompressLZ {
    public static void compress(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            new LempelZivAlgorithm(inputStream, outputStream).compress();
        }
    }

    public static void decompress(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {

            // new LempelZivAlgorithm(inputStream, outputStream).decompress();
        }
    }

    public static void printHelp() {
        System.out.println("You must specify a flag (-c, -d or -h) and a file path.");
    }

    public static void main(String[] args) throws IOException {
        String flag = args.length >= 1 ? args[0] : null;
        switch (flag) {
        case "-c":
            compress(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "-d":
            decompress(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "-h":
            printHelp();
            break;
        }
    }
}