import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;

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

class RingBuffer {
    private final byte[] buffer;
    private final int indexMask;
    private int startIndex = 0;
    private int endIndex = 0;
    private int size = 0;

    public RingBuffer(int capacity) {
        if (Integer.highestOneBit(capacity) != capacity) {
            throw new IllegalArgumentException("capacity was not power of 2");
        }
        indexMask = ~(1 << Integer.numberOfTrailingZeros(capacity));
        buffer = new byte[capacity];
    }

    public int getSize() {
        return size;
    }

    private void pushByte(byte b) {
        buffer[endIndex] = b;
        endIndex = (endIndex + 1) & indexMask;
        size++;
    }

    public void drop(int bytes) {
        if (bytes > size) {
            throw new IllegalStateException("cannot drop more than size");
        }
        startIndex = (startIndex + bytes) & indexMask;
        size -= bytes;
    }

    public void add(byte b) {
        if (size == buffer.length) {
            throw new IllegalStateException("buffer is full");
        }
        pushByte(b);
    }

    public void addAll(byte[] array, int offset, int length) {
        int count = length - offset;
        if (size + count > buffer.length) {
            throw new IllegalArgumentException(
                    "not enough space in buffer " + (buffer.length - size) + " B left, adding " + count);
        }
        for (int i = offset; i < length; i++) {
            pushByte(array[i]);
        }
    }

    public byte get(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("attempted to read index " + index + " when size was " + size);
        }
        return buffer[(startIndex + index) & indexMask];
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
        entryCount = 0;
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
        byte[] fullChunk = finalizedOutput;
        finalizedOutput = null;
        return fullChunk;
    }

    public ArrayWithSize getFinalChunk() {
        finalizeEntryBlock();
        byte[] finalChunk = outputChunk;
        outputChunk = null;
        return new ArrayWithSize(finalChunk, entryIndex);
    }
}

class SlidingWindow {
    private final RingBuffer buffer;
    private final int windowSize;
    private final int lookaheadSize;
    private final int minMatchLength;
    private int offset = 0;
    private int divider = 0;

    private final int[][] indexLut = new int[0xFFFF][];

    public SlidingWindow(RingBuffer buffer, int windowSize, int lookaheadSize, int minMatchLength) {
        this.buffer = buffer;
        this.windowSize = windowSize;
        this.lookaheadSize = lookaheadSize;
        this.minMatchLength = minMatchLength;
        
        for (int i = 0; i < indexLut.length; i++) {
            indexLut[i] = new int[1];
            indexLut[i][0] = 0;
        }
    }

    private static int[] expandArray(int[] array) {
        int[] newArray = new int[array.length*2];
        for (int i = 0; i < array.length; i++) {
            newArray[i] = array[i];
        }
        return newArray;
    }

    private void addEntry(int index, int value) {
        int[] entries = indexLut[index];
        if (entries[0] + 1 >= entries.length) {
            int[] newEntries = expandArray(entries);
            indexLut[index] = newEntries;
            entries = newEntries;
        }
        entries[++entries[0]] = value;
    }

    private void removeEntry(int index, int value) {
        int[] entries = indexLut[index];
        boolean moveBack = false;
        for (int i = 1; i <= entries[0]; i++) {
            if (moveBack) {
                entries[i - 1] = entries[i];
            }
            if (entries[i] == value) {
                moveBack = true;
            }
        }
        if (moveBack) {
            entries[0]--;
        }
    }

    public void setDivider(int index) {
        int oldDivider = divider;
        int oldOffset = offset;
        divider = index;
        offset = divider - windowSize;
        if (offset < 0) {
            offset = 0;
        }

        if (divider - oldDivider > 0) {
            for (int i = oldOffset; i < offset; i++) {
                int entry = (buffer.get(i) & 0xFF) << 8 | (buffer.get(i + 1) & 0xFF);
                removeEntry(entry, i);
            }
            for (int i = oldDivider; i < divider; i++) {
                int entry = (buffer.get(i) & 0xFF) << 8 | (buffer.get(i + 1) & 0xFF);
                addEntry(entry, i);
            }
        }

    }

    public Match findMatch() {
        int lookaheadEnd = Math.min(lookaheadSize, buffer.getSize() - this.divider);
        if (lookaheadEnd < minMatchLength) {
            return null;
        }
        int twoBytes = (buffer.get(this.divider) & 0xFF) << 8 | (buffer.get(this.divider + 1) & 0xFF);

        int[] matches = indexLut[twoBytes];

        int matchIndex = -1;
        int matchLength = 0;
        for (int j = 1; j <= matches[0]; j++) {
            int windowIndex = matches[j];
            int i;
            for (i = 1; i < lookaheadEnd && i + windowIndex < this.divider; i++) {
                if (buffer.get(divider + i) != buffer.get(windowIndex + i)) {
                    break;
                }
            }
            if (i > matchLength) {
                matchIndex = windowIndex;
                matchLength = i;
            }
        }

        if (matchLength > minMatchLength)

        {
            return new Match(this.divider - matchIndex, matchLength);
        } else {
            return null;
        }
    }

    public byte getByte(int index) {
        if (index < offset || index >= divider) {
            throw new IndexOutOfBoundsException();
        }

        return buffer.get(index);
    }
}

class LempelZivAlgorithm {
    private static final int MIN_MATCH_LENGTH = Match.SERIALIZED_BYTES + 1; // 5bytes

    private static final int LOOKAHEAD_SIZE = 256;
    private static final int WINDOW_SIZE = 32768;
    private static final int READ_CHUNK_SIZE = 131072; // 128 KiB
    private static final int OUTPUT_CHUNK_SIZE = 16777220; // 16 MiB
    private static final int RING_BUFFER_CAPACITY = 1048576; // 1 MiB
    private static final int READ_THRESHOLD = RING_BUFFER_CAPACITY - Math.min(LOOKAHEAD_SIZE, WINDOW_SIZE);

    private final InputStream inputStream;
    private final OutputStream outputStream;

    public LempelZivAlgorithm(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = new BufferedInputStream(inputStream);
        this.outputStream = new BufferedOutputStream(outputStream);
    }

    private int br;

    private int readChunkIntoRingBuffer(RingBuffer buffer) throws IOException {
        byte[] array = new byte[RING_BUFFER_CAPACITY - buffer.getSize()];
        int bytesRead = inputStream.read(array);
        if (bytesRead != -1) {
            br += bytesRead;
            System.out.printf("%.2f MiB%n", br / (1024.0 * 1024.0));
            buffer.addAll(array, 0, bytesRead);
            return bytesRead;
        }
        return 0;
    }

    public void compress() throws IOException {
        RingBuffer buffer = new RingBuffer(RING_BUFFER_CAPACITY);
        SlidingWindow window = new SlidingWindow(buffer, WINDOW_SIZE, LOOKAHEAD_SIZE, MIN_MATCH_LENGTH);
        OutputWriter outputWriter = new OutputWriter(OUTPUT_CHUNK_SIZE);

        readChunkIntoRingBuffer(buffer);

        boolean moreDataInStream = true;
        // fill read-buffer with first arrays
        for (int lookaheadIndex = 0; lookaheadIndex < buffer.getSize();) {
            if (moreDataInStream && lookaheadIndex >= READ_THRESHOLD) {
                buffer.drop(READ_CHUNK_SIZE);
                int moveBack = readChunkIntoRingBuffer(buffer);
                if (moveBack == 0) {
                    moreDataInStream = false;
                }
                lookaheadIndex -= moveBack;
            }
            window.setDivider(lookaheadIndex);

            Match match = window.findMatch();
            if (match == null) {
                // System.out.printf("%c", buffer.get(lookaheadIndex) & 0xFF);
                outputWriter.writeByte(buffer.get(lookaheadIndex));
                lookaheadIndex++;
            } else {
                // System.out.printf("(%d, %d)", match.getDistance(), match.getLength());
                lookaheadIndex += match.getLength();
                outputWriter.writeMatch(match);
            }

            byte[] output = outputWriter.getFullChunk();
            if (output != null) {
                outputStream.write(output);
            }
        }

        ArrayWithSize finalOutput = outputWriter.getFinalChunk();
        if (finalOutput != null) {
            outputStream.write(finalOutput.array, 0, finalOutput.size);
        }

        outputStream.flush();
    }

    public void decompress() throws IOException {
        RingBuffer window = new RingBuffer(WINDOW_SIZE);
        byte[] writeBuffer = new byte[WINDOW_SIZE];

        boolean moreData = true;
        while (moreData) {
            int firstByte = inputStream.read();
            if (firstByte == -1) {
                moreData = false;
                break;
            }
            byte entries = (byte) firstByte;
            boolean isCompressed = entries < 0 ? true : false;
            byte[] entry = new byte[isCompressed ? Match.SERIALIZED_BYTES : Byte.BYTES];
            entries = (byte) Math.abs(entries);

            for (int i = 0; i < entries; i++) {
                int writeLength = 0;
                int bytesRead = inputStream.read(entry);
                if (bytesRead != entry.length) {
                    throw new IOException("couldnt read entry");
                }
                if (isCompressed) {
                    Match match = Match.deserialize(entry);
                    int base = window.getSize() - match.getDistance();
                    for (int j = 0; j < match.getLength(); j++) {
                        writeBuffer[j] = window.get(base + j);
                    }
                    writeLength = match.getLength();
                } else {
                    writeBuffer[0] = entry[0];
                    writeLength = 1;
                }
                if (window.getSize() + writeLength >= WINDOW_SIZE) {
                    window.drop((window.getSize() + writeLength) - WINDOW_SIZE);
                }
                outputStream.write(writeBuffer, 0, writeLength);
                window.addAll(writeBuffer, 0, writeLength);
            }
        }

        outputStream.flush();
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

            new LempelZivAlgorithm(inputStream, outputStream).decompress();
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