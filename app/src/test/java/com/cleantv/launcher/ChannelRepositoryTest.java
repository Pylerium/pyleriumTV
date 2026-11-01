package com.cleantv.launcher;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelRepositoryTest {
    @Test public void newChannelsReceiveStableNamesAndNumbers() {
        List<ChannelRepository.Channel> channels = new ArrayList<>();
        ChannelRepository.Channel first = ChannelRepository.addChannel(channels);
        ChannelRepository.Channel second = ChannelRepository.addChannel(channels);
        assertEquals("1", first.number);
        assertEquals("2", second.number);
        assertEquals("NEW CHANNEL", first.name);
        assertTrue(first.id.length() > 0);
    }

    @Test public void channelChoosesCurrentlyScheduledMedia() {
        ChannelRepository.Channel channel = new ChannelRepository.Channel();
        long now = System.currentTimeMillis();
        channel.programs.add(new ChannelRepository.Program("Earlier", "content://earlier", now - 7200000, now - 3600000));
        channel.programs.add(new ChannelRepository.Program("Now", "content://current", now - 1000, now + 3600000));
        assertEquals("content://current", channel.currentUri());
    }
}
