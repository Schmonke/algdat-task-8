import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Stack;
import java.io.DataInputStream;
import java.io.DataOutputStream;

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

    public SlidingWindow(RingBuffer buffer, int windowSize, int lookaheadSize, int minMatchLength) {
        this.buffer = buffer;
        this.windowSize = windowSize;
        this.lookaheadSize = lookaheadSize;
        this.minMatchLength = minMatchLength;
    }

    public void setDivider(int index) {
        divider = index;
        offset = divider - windowSize;
        if (offset < 0) {
            offset = 0;
        }
    }

    public Match findMatch() {
        int lookaheadEnd = Math.min(lookaheadSize, buffer.getSize() - this.divider);

        int matchIndex = -1;
        int matchLength = 0;
        for (int i = this.offset; i < this.divider; i++) {
            int j;
            int jMax = Math.min(lookaheadEnd, this.divider - i);
            for (j = 0; j < jMax; j++) {
                if (buffer.get(i + j) != buffer.get(this.divider + j)) {
                    break;
                }
            }
            if (j > matchLength) {
                matchIndex = i;
                matchLength = j;
            }
        }

        if (matchLength > minMatchLength) {
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

    private static final int LOOKAHEAD_SIZE = 16384;
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

    private int readChunkIntoRingBuffer(RingBuffer buffer) throws IOException {
        byte[] array = new byte[RING_BUFFER_CAPACITY - buffer.getSize()];
        int bytesRead = inputStream.read(array);
        if (bytesRead != -1) {
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

class VariableWidthEncoding {
    private static final int BITS_PER_BYTE = 7;
    private static final int READ_BITS_MASK = (1 << BITS_PER_BYTE) - 1; // 0b01111111
    private static final int CONTINUE_BIT = 0b10000000;
    private static final int MAX_BYTES = (int) Math.ceil((double) Integer.SIZE / BITS_PER_BYTE);

    public static void encode(int value, OutputStream outputStream) throws IOException {
        while (value != 0) {
            int bits = value & READ_BITS_MASK;
            value >>>= BITS_PER_BYTE;
            if (value != 0) {
                bits |= CONTINUE_BIT;
            }
            outputStream.write(bits);
        }
    }

    public static int decode(InputStream inputStream) throws IOException {
        int value = 0;
        int shift = 0;
        int b;
        int bytesRead = 0;

        while (bytesRead++ < MAX_BYTES && (b = inputStream.read()) != -1) {
            int bits = b & READ_BITS_MASK;
            value |= bits << shift;
            shift += BITS_PER_BYTE;
            if ((b & CONTINUE_BIT) == 0) {
                break;
            }
        }

        return value;
    }
}

class BitSegment {
    private final int size;
    private final long bits;

    public BitSegment(int size, long bits) {
        this.size = size;
        this.bits = bits;
    }

    public int getSize() {
        return size;
    }

    public long getBits() {
        return bits;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(size);
        for (int i = size - 1; i >= 0; i--) {
            builder.append((bits & (1 << i)) >>> i);
        }
        return builder.toString();
    }
}

class BitBuffer {
    private int size = 0;
    private long bits = 0;

    public void add(BitSegment bitSegment) {
        long newBits = bitSegment.getBits();
        int newBitsSize = bitSegment.getSize();
        size += newBitsSize;
        bits = (bits << newBitsSize) | newBits;
    }

    public int read() {
        if (size < 8) {
            return -1;
        }
        int shift = size - Byte.SIZE;
        int b = (int) ((bits & (0xFF << shift)) >>> shift);
        size -= Byte.SIZE;
        return b;
    }

    public int readFinal() {
        if (size == 0) {
            return -1;
        }
        int b = (int) (bits & ((1 << size) - 1)) << (Byte.SIZE - size);
        return b;
    }
}

class Constants {
    public static final int BYTE_MAX_POSSIBILITIES = 1 << Byte.SIZE;
}

class HuffmanFrequencies {
    int[] frequencies = new int[Constants.BYTE_MAX_POSSIBILITIES];

    private void setFrequency(byte c, int frequency) {
        frequencies[c & 0xFF] = frequency;
    }

    public void increment(byte c) {
        frequencies[c & 0xFF]++;
    }

    public int[] getFrequencies() {
        return frequencies.clone();
    }

    public void serialize(OutputStream outputStream) throws IOException {
        int nonZeroFrequencies = (int) Arrays.stream(frequencies).filter(x -> x != 0).count();
        VariableWidthEncoding.encode(nonZeroFrequencies, outputStream);
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                outputStream.write(i);
                VariableWidthEncoding.encode(frequencies[i], outputStream);
            }
        }
    }

    public static HuffmanFrequencies deserialize(InputStream inputStream) throws IOException {
        HuffmanFrequencies instance = new HuffmanFrequencies();
        int frequencyCount =  VariableWidthEncoding.decode(inputStream);
        for (int i = 0; i < frequencyCount; i++) {
            byte c = (byte) inputStream.read();
            int frequency = VariableWidthEncoding.decode(inputStream);
            instance.setFrequency(c, frequency);
        }
        return instance;
    }
}

class HuffmanTree {
    private final BitSegment[] encodeLut;
    private final Node root;
    private Node decodeNode;

    public HuffmanTree(HuffmanFrequencies frequencies) {
        root = generateTree(frequencies.getFrequencies());
        decodeNode = root;
        encodeLut = createEncodeLut(root);
    }

    public BitSegment encode(byte c) {
        return encodeLut[c & 0xFF];
    }

    public int decode(byte c, byte[] out) {
        int bytes = 0;
        for (int i = Byte.SIZE - 1; i >= 0; i--) {
            int bit = ((c & 0xFF) >>> i) & 1;
            decodeNode = decodeNode.getChild(bit);
            if (decodeNode.isLeaf()) {
                out[bytes++] = decodeNode.getValue();
                decodeNode = root;
            }
        }
        return bytes;
    }

    private BitSegment[] createEncodeLut(Node root) {
        class Entry {
            public final Node node;
            public final BitSegment bits;

            public Entry(Node node, BitSegment bits) {
                this.node = node;
                this.bits = bits;
            }
        }
        BitSegment[] encodeLut = new BitSegment[Constants.BYTE_MAX_POSSIBILITIES];
        if (root == null) {
            return encodeLut;
        }

        Stack<Entry> stack = new Stack<>();

        stack.push(new Entry(root, new BitSegment(0, 0)));

        while (stack.size() > 0) {
            Entry entry = stack.pop();
            Node node = entry.node;
            if (node.isLeaf()) {
                encodeLut[entry.node.value & 0xFF] = entry.bits;
                continue;
            }

            int newSize = entry.bits.getSize() + 1;
            long oldBits = entry.bits.getBits();

            Node left = node.getChild(0);
            Node right = node.getChild(1);

            BitSegment leftBits = new BitSegment(newSize, oldBits << 1);
            BitSegment rightBits = new BitSegment(newSize, (oldBits << 1) | 1);

            stack.push(new Entry(right, rightBits));
            stack.push(new Entry(left, leftBits));
        }

        return encodeLut;
    }

    private Node generateTree(int[] frequencies) {
        Comparator<Node> nodeComparator = Comparator.comparing(Node::getFrequency);
        PriorityQueue<Node> nodes = new PriorityQueue<>(frequencies.length, nodeComparator);

        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                nodes.add(new Node(frequencies[i], (byte) i));
            }
        }

        while (nodes.size() > 1) {
            Node right = nodes.poll();
            Node left = nodes.poll();
            Node parent = new Node(left, right);
            nodes.add(parent);
        }

        return nodes.poll();
    }

    static class Node {
        public Node(Node left, Node right) {
            this.left = left;
            this.right = right;
            if (left != null)
                this.frequency += left.frequency;
            if (right != null)
                this.frequency += right.frequency;
        }

        public Node(int frequency, byte value) {
            this.frequency = frequency;
            this.value = value;
        }

        private Node left;
        private Node right;
        private int frequency;
        private byte value;

        public boolean isLeaf() {
            return left == null && right == null;
        }

        public byte getValue() {
            return value;
        }

        public int getFrequency() {
            return frequency;
        }

        public Node getChild(int pos) {
            switch (pos) {
            case 0:
                return left;
            case 1:
                return right;
            default:
                throw new IllegalArgumentException("pos must be 0 or 1");
            }
        }
    }
}

class HuffmanAlgorithm {
    private static final int BLOCK_SIZE = 16777220; // 16 MiB

    private final InputStream inputStream;
    private final OutputStream outputStream;

    public HuffmanAlgorithm(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = new BufferedInputStream(inputStream);
        this.outputStream = new BufferedOutputStream(outputStream);
    }

    private void compressBlock(byte[] block, int length) throws IOException {
        System.out.println("Constructing frequency table...");
        HuffmanFrequencies frequencies = new HuffmanFrequencies();
        for (int i = 0; i < length; i++) {
            frequencies.increment(block[i]);
        }

        frequencies.serialize(outputStream);
        VariableWidthEncoding.encode(length, outputStream);

        System.out.printf("Compressing block (%d B)...%n", length);
        BitBuffer bitBuffer = new BitBuffer();
        HuffmanTree tree = new HuffmanTree(frequencies);

        for (int i = 0; i < length; i++) {
            BitSegment bitSegment = tree.encode(block[i]);
            bitBuffer.add(bitSegment);
            int b;
            while ((b = bitBuffer.read()) != -1) {
                outputStream.write(b);
            }
        }
        int b = bitBuffer.readFinal();
        if (b != -1) {
            outputStream.write(b);
        }
    }

    public void compress() throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        int length;
        while ((length = inputStream.read(block)) != -1) {
            compressBlock(block, length);
        }
        outputStream.flush();
    }

    private int decompressBlock(byte[] block) throws IOException {
        System.out.println("Reading frequency table..");
        HuffmanFrequencies frequencies = HuffmanFrequencies.deserialize(inputStream);
        int blockLength = VariableWidthEncoding.decode(inputStream);

        System.out.printf("Decompressing block (%d B)...%n", blockLength);
        byte[] decodeBuffer = new byte[Byte.SIZE];
        HuffmanTree tree = new HuffmanTree(frequencies);
        for (int i = 0; i < blockLength;) {
            int bits = inputStream.read();
            if (bits == -1) {
                throw new IOException("Unexpected end of data.");
            }
            int bytes = tree.decode((byte) bits, decodeBuffer);
            for (int j = 0; j < bytes; j++) {
                block[i++] = decodeBuffer[j];
                if (i >= blockLength) {
                    break;
                }
            }
        }

        return blockLength;
    }

    public void decompress() throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        int length;
        while (true) {
            length = decompressBlock(block);
            outputStream.write(block, 0, length);

            inputStream.mark(1);
            if (inputStream.read() == -1) {
                break;
            }
            inputStream.reset();
        }
        outputStream.flush();
    }
}

class XCompress {
    public static void compress(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("two file paths must be provided.");
            return;
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            ByteArrayOutputStream lzOutputStream = new ByteArrayOutputStream();
            new LempelZivAlgorithm(inputStream, lzOutputStream).compress();
            ByteArrayInputStream huffmanInputStream = new ByteArrayInputStream(lzOutputStream.toByteArray());
            new HuffmanAlgorithm(huffmanInputStream, outputStream).compress();
        }
    }

    public static void decompress(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("two file paths must be provided.");
            return;
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            ByteArrayOutputStream huffmanOutputStream = new ByteArrayOutputStream();
            new HuffmanAlgorithm(inputStream, huffmanOutputStream).decompress();
            ByteArrayInputStream lzInputstream = new ByteArrayInputStream(huffmanOutputStream.toByteArray());
            new LempelZivAlgorithm(lzInputstream, outputStream).decompress();
        }
    }

    public static void printHelp() {
        System.out.println(
            "You must specify a flag (-c, -d or -h) and one file path for source and one for target file.\n" +
            "Flags:\n" +
            " -c: compress file\n" +
            " -d: decompress file\n" +
            " -h: show this help\n" +
            "\n" +
            "E.g. java XCompress -c uncompressed_file compressed_file\n" +
            "     java XCompress -d compressed_file decompressed_file"
        );
    }

    public static void main(String[] args) throws IOException {
        String flag = args.length >= 1 ? args[0] : "-h";
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