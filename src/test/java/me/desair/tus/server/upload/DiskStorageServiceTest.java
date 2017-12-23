package me.desair.tus.server.upload;

import me.desair.tus.server.creation.CreationPatchRequestHandler;
import me.desair.tus.server.exception.InvalidUploadOffsetException;
import me.desair.tus.server.exception.UploadNotFoundException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DiskStorageServiceTest {

    public static final String UPLOAD_URL = "/upload/test";
    private DiskStorageService storageService;

    @Mock
    private UploadIdFactory idFactory;

    private static Path storagePath;

    @BeforeClass
    public static void setupDataFolder() throws IOException {
        storagePath = Paths.get("target", "tus", "data").toAbsolutePath();
        Files.createDirectories(storagePath);
    }

    @AfterClass
    public static void destroyDataFolder() throws IOException {
        FileUtils.deleteDirectory(storagePath.toFile());
    }

    @Before
    public void setUp() {
        when(idFactory.getUploadURI()).thenReturn(UPLOAD_URL);
        when(idFactory.createId()).thenReturn(UUID.randomUUID());
        when(idFactory.readUploadId(anyString())).then(new Answer<UUID>() {
            @Override
            public UUID answer(final InvocationOnMock invocation) throws Throwable {
                return UUID.fromString(StringUtils.substringAfter(invocation.getArguments()[0].toString(),
                        UPLOAD_URL + "/"));
            }
        });

        storageService = new DiskStorageService(idFactory, storagePath.toString());
    }

    @Test
    public void getMaxUploadSize() throws Exception {
        storageService.setMaxUploadSize(null);
        assertThat(storageService.getMaxUploadSize(), is(0L));

        storageService.setMaxUploadSize(0L);
        assertThat(storageService.getMaxUploadSize(), is(0L));

        storageService.setMaxUploadSize(-10L);
        assertThat(storageService.getMaxUploadSize(), is(0L));

        storageService.setMaxUploadSize(372036854775807L);
        assertThat(storageService.getMaxUploadSize(), is(372036854775807L));
    }

    @Test
    public void getUploadURI() throws Exception {
        assertThat(storageService.getUploadURI(), is(UPLOAD_URL));
    }

    @Test
    public void create() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        info.setEncodedMetadata("Encoded Metadata");

        info = storageService.create(info);

        assertThat(info.getId(), is(notNullValue()));
        assertThat(info.getOffset(), is(0L));
        assertThat(info.getLength(), is(10L));
        assertThat(info.getEncodedMetadata(), is("Encoded Metadata"));

        assertTrue(Files.exists(getUploadInfoPath(info.getId())));
    }

    @Test
    public void getUploadInfoById() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        info.setEncodedMetadata("Encoded Metadata");

        info = storageService.create(info);

        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        UploadInfo readInfo = storageService.getUploadInfo(info.getId());

        assertTrue(readInfo != info);
        assertThat(readInfo.getId(), is(info.getId()));
        assertThat(readInfo.getOffset(), is(0L));
        assertThat(readInfo.getLength(), is(10L));
        assertThat(readInfo.getEncodedMetadata(), is("Encoded Metadata"));
    }

    @Test
    public void getUploadInfoByFakeId() throws Exception {
        UploadInfo readInfo = storageService.getUploadInfo(UUID.randomUUID());
        assertThat(readInfo, is(nullValue()));
    }

    @Test
    public void getUploadInfoByUrl() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setLength(10L);
        info.setEncodedMetadata("Encoded Metadata");

        info = storageService.create(info);

        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        UploadInfo readInfo = storageService.getUploadInfo(UPLOAD_URL + "/" + info.getId());

        assertTrue(readInfo != info);
        assertThat(readInfo.getId(), is(info.getId()));
        assertThat(readInfo.getOffset(), is(0L));
        assertThat(readInfo.getLength(), is(10L));
        assertThat(readInfo.getEncodedMetadata(), is("Encoded Metadata"));
    }

    @Test
    public void update() throws Exception {
        UploadInfo info1 = new UploadInfo();
        info1.setLength(10L);
        info1.setEncodedMetadata("Encoded Metadata");

        info1 = storageService.create(info1);

        assertTrue(Files.exists(getUploadInfoPath(info1.getId())));

        UploadInfo info2 = new UploadInfo();
        info2.setId(info1.getId());
        info2.setLength(10L);
        info2.setOffset(8L);
        info2.setEncodedMetadata("Updated Encoded Metadata");

        storageService.update(info2);

        UploadInfo readInfo = storageService.getUploadInfo(info1.getId());

        assertTrue(readInfo != info1);
        assertTrue(readInfo != info2);
        assertThat(info2.getId(), is(info1.getId()));
        assertThat(readInfo.getId(), is(info1.getId()));
        assertThat(readInfo.getOffset(), is(8L));
        assertThat(readInfo.getLength(), is(10L));
        assertThat(readInfo.getEncodedMetadata(), is("Updated Encoded Metadata"));
    }

    @Test
    public void append() throws Exception {
        String part1 = "This is part 1";
        String part2 = "This is the second part of my upload";

        //Create our upload with the correct length
        UploadInfo info = new UploadInfo();
        info.setLength((long) (part1.getBytes().length + part2.getBytes().length));
        info.setEncodedMetadata("Encoded Metadata");

        info = storageService.create(info);
        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        //Write the first part of the upload
        storageService.append(info, IOUtils.toInputStream(part1, StandardCharsets.UTF_8));
        assertThat(new String(Files.readAllBytes(getUploadDataPath(info.getId()))), is(part1));

        UploadInfo readInfo = storageService.getUploadInfo(info.getId());

        assertThat(readInfo.getId(), is(info.getId()));
        assertThat(readInfo.getOffset(), is((long) part1.getBytes().length));
        assertThat(readInfo.getLength(), is(info.getLength()));
        assertThat(readInfo.getEncodedMetadata(), is("Encoded Metadata"));

        //Write the second part of the upload
        storageService.append(info, IOUtils.toInputStream(part2, StandardCharsets.UTF_8));
        assertThat(new String(Files.readAllBytes(getUploadDataPath(info.getId()))), is(part1 + part2));

        readInfo = storageService.getUploadInfo(info.getId());

        assertThat(readInfo.getId(), is(info.getId()));
        assertThat(readInfo.getOffset(), is(info.getLength()));
        assertThat(readInfo.getLength(), is(info.getLength()));
        assertThat(readInfo.getEncodedMetadata(), is("Encoded Metadata"));
    }

    @Test
    public void appendExceedingMaxSingleUpload() throws Exception {
        String content = "This is an upload that is too large";

        storageService.setMaxUploadSize(17L);

        //Create our upload with the correct length
        UploadInfo info = new UploadInfo();
        info.setLength(17L);

        info = storageService.create(info);
        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        //Write the content of the upload
        storageService.append(info, IOUtils.toInputStream(content, StandardCharsets.UTF_8));

        //The storage service should protect itself an only write until the maximum number of bytes allowed
        assertThat(new String(Files.readAllBytes(getUploadDataPath(info.getId()))), is("This is an upload"));
    }

    @Test
    public void appendExceedingMaxMultiUpload() throws Exception {
        String part1 = "This is an ";
        String part2 = "upload that is too large";

        storageService.setMaxUploadSize(17L);

        //Create our upload with the correct length
        UploadInfo info = new UploadInfo();
        info.setLength(17L);

        info = storageService.create(info);
        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        //Write the content of the upload in two parts
        storageService.append(info, IOUtils.toInputStream(part1, StandardCharsets.UTF_8));
        info = storageService.getUploadInfo(info.getId());
        storageService.append(info, IOUtils.toInputStream(part2, StandardCharsets.UTF_8));

        //The storage service should protect itself an only write until the maximum number of bytes allowed
        assertThat(new String(Files.readAllBytes(getUploadDataPath(info.getId()))), is("This is an upload"));
    }

    @Test(expected = UploadNotFoundException.class)
    public void appendOnFakeUpload() throws Exception {
        String content = "This upload was not created before";

        //Create our fake upload
        UploadInfo info = new UploadInfo();
        info.setId(UUID.randomUUID());
        info.setLength((long) (content.getBytes().length));

        //Write the content of the upload
        storageService.append(info, IOUtils.toInputStream(content, StandardCharsets.UTF_8));
    }

    @Test(expected = InvalidUploadOffsetException.class)
    public void appendOnInvalidOffset() throws Exception {
        String content = "This is an upload that is too large";

        storageService.setMaxUploadSize(17L);

        //Create our upload with the correct length
        UploadInfo info = new UploadInfo();
        info.setLength(17L);

        info = storageService.create(info);
        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        info.setOffset(3L);
        storageService.update(info);

        //Write the content of the upload
        storageService.append(info, IOUtils.toInputStream(content, StandardCharsets.UTF_8));
    }

    @Test
    public void getUploadedBytes() throws Exception {
        String content = "This is the content of my upload";

        //Create our upload with the correct length
        UploadInfo info = new UploadInfo();
        info.setLength((long) content.getBytes().length);

        info = storageService.create(info);
        assertTrue(Files.exists(getUploadInfoPath(info.getId())));

        //Write the content of the upload
        storageService.append(info, IOUtils.toInputStream(content, StandardCharsets.UTF_8));
        assertTrue(Files.exists(getUploadDataPath(info.getId())));

        try(InputStream uploadedBytes = storageService.getUploadedBytes(UPLOAD_URL + "/" + info.getId())) {

            assertThat(IOUtils.toString(uploadedBytes, StandardCharsets.UTF_8),
                    is("This is the content of my upload"));
        }
    }

    @Test
    public void cleanupExpiredUploads() throws Exception {
        //TODO
    }

    private Path getUploadInfoPath(final UUID id) {
        return storagePath.resolve("uploads").resolve(id.toString()).resolve("info");
    }

    private Path getUploadDataPath(final UUID id) {
        return storagePath.resolve("uploads").resolve(id.toString()).resolve("data");
    }
}