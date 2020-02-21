package com.bookoo;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.bookoo.jukebox.JukeboxProtos;
import com.bookoo.jukebox.JukeboxProtos.Message;
import com.bookoo.jukebox.JukeboxProtos.Message.MessageType;
import com.bookoo.jukebox.JukeboxProtos.SongPreamble;

import javazoom.spi.mpeg.sampled.file.MpegEncoding;

/**
 * 
 */
public final class AudioSink implements Runnable {
    private final int port;
    private State state;
    private SourceDataLine line;

    private enum State {
        IDLE, AUDIO_LINE_STARTED, AUDIO_LINE_STREAMING
    }

    AudioSink() {
        this.port = 2187;
        this.state = State.IDLE;
        this.line = null;
    }

    AudioSink(AudioInputStream in) {
        this.port = 2187;
        this.state = State.IDLE;
        this.line = null;
    }

    public static void main(String[] args) throws Exception {
        AudioSink audioSink = new AudioSink();
        ExecutorService es = Executors.newSingleThreadExecutor();

        Future<?> ignore = es.submit(audioSink);

        ignore.get();
        es.shutdown();
        es.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Override
    public void run() {

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            Socket clientSocket = serverSocket.accept();

            while (true) {
                Message message = JukeboxProtos.Message.parseDelimitedFrom(clientSocket.getInputStream());

                if (message == null) {
                    System.err.println("Error while reading message, attempting to reset state.");
                    resetState();
                    Message audioConsumedMessage = Message.newBuilder().setMessageType(MessageType.AUDIO_STREAM_RESET).build();
                    audioConsumedMessage.writeDelimitedTo(clientSocket.getOutputStream());
                    continue;
                }

                if (message.getMessageType() == MessageType.SONG_PREAMBLE) {
                    SongPreamble preamble = message.getSongPreamble();

                    openAudioLine(preamble);
                } else if (message.getMessageType() == MessageType.SONG_CHUNK) {
                    if (state == State.AUDIO_LINE_STARTED || state == State.AUDIO_LINE_STREAMING) {
                        InputStream songChunk = message.getSongChunk().getChunk().newInput();
                        byte[] chunk = songChunk.readAllBytes();

                        if (chunk.length > 0) {
                            state = State.AUDIO_LINE_STREAMING;
                            getAudioLine().write(chunk, 0, chunk.length);
                        } else {
                            closeAudioLine();
                            Message audioConsumedMessage = Message.newBuilder().setMessageType(MessageType.AUDIO_STREAM_CONSUMED).build();
                            audioConsumedMessage.writeDelimitedTo(clientSocket.getOutputStream());
                        }
                    } else {
                        System.err.println("Received a SongChunk  message without receiving a SongPreamble first. Ignoring it");
                        resetState();
                        Message audioConsumedMessage = Message.newBuilder().setMessageType(MessageType.AUDIO_STREAM_RESET).build();
                        audioConsumedMessage.writeDelimitedTo(clientSocket.getOutputStream());
                    }
                } else {
                    System.err.println("Received an unknown message type. Ignoring it");
                    resetState();
                    Message audioConsumedMessage = Message.newBuilder().setMessageType(MessageType.AUDIO_STREAM_RESET).build();
                    audioConsumedMessage.writeDelimitedTo(clientSocket.getOutputStream());
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SourceDataLine getAudioLine() {
        return line;
    }

    private void openAudioLine(SongPreamble preamble) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(translate(preamble.getEncoding()), preamble.getSampleRate(), preamble.getSampleSizeInBits(), preamble.getChannels(), preamble.getFrameSize(), preamble.getFrameRate(), preamble.getBigEndian());
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();
        state = State.AUDIO_LINE_STARTED;
    }

    private void closeAudioLine() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }

        state = State.IDLE;
    }

    private void resetState() {
        state = State.IDLE;

        if (line != null) {
            line.close();
            line = null;
        }
    }

    private AudioFormat.Encoding translate(SongPreamble.Encoding encoding) {
        switch (encoding) {
            case ALAW:
                return AudioFormat.Encoding.ALAW;
            case PCM_FLOAT:
                return AudioFormat.Encoding.PCM_FLOAT;
            case PCM_SIGNED:
                return AudioFormat.Encoding.PCM_SIGNED;
            case PCM_UNSIGNED:
                return AudioFormat.Encoding.PCM_UNSIGNED;
            case ULAW:
                return AudioFormat.Encoding.ULAW;
            case MPEG1L1:
                return MpegEncoding.MPEG1L1;
            case MPEG1L2:
                return MpegEncoding.MPEG1L2;
            case MPEG1L3:
                return MpegEncoding.MPEG1L3;
            case MPEG2DOT5L1:
                return MpegEncoding.MPEG2DOT5L1;
            case MPEG2DOT5L2:
                return MpegEncoding.MPEG2DOT5L2;
            case MPEG2DOT5L3:
                return MpegEncoding.MPEG2DOT5L3;
            case MPEG2L1:
                return MpegEncoding.MPEG2L1;
            case MPEG2L2:
                return MpegEncoding.MPEG2L2;
            case MPEG2L3:
                return MpegEncoding.MPEG2L3;
            default:
                throw new RuntimeException("Unknown SongPreamble.Encoding " + encoding);
        }
    }
}
