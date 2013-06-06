package gsn.vsensor;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import gsn.beans.DataField;
import gsn.beans.StreamElement;

import org.apache.log4j.Logger;

public class GPSSvToRawBinary extends BridgeVirtualSensorPermasense {
	
	private static String GPS_TIME_FIELD_NAME = "gps_unixtime";
	private static Short GPS_RAW_DATA_VERSION = 1;

	private static String GPS_ITOW_FIELD_NAME = "gps_time";
	private static String GPS_WEEK_FIELD_NAME = "gps_week";
	private static String GPS_NUMSV_FIELD_NAME = "num_sv";
	private static String GPS_CPMES_FIELD_NAME = "carrier_phase";
	private static String GPS_PRMES_FIELD_NAME = "pseudo_range";
	private static String GPS_DOMES_FIELD_NAME = "doppler";
	private static String GPS_SV_FIELD_NAME = "space_vehicle";
	private static String GPS_MESQI_FIELD_NAME = "measurement_quality";
	private static String GPS_CNO_FIELD_NAME = "signal_strength";
	private static String GPS_LLI_FIELD_NAME = "loss_of_lock";
	
	private static final transient Logger logger = Logger.getLogger(GPSSvToRawBinary.class);

	private static final DataField[] dataField = {
		new DataField("POSITION", "INTEGER"),
		new DataField("GENERATION_TIME", "BIGINT"),
		new DataField("TIMESTAMP", "BIGINT"),
		new DataField("DEVICE_ID", "INTEGER"),
		new DataField("GPS_UNIXTIME", "BIGINT"),

		new DataField("SENSOR_TYPE", "VARCHAR(16)"),
		new DataField("GPS_RAW_DATA_VERSION", "SMALLINT"),
		new DataField("GPS_SATS", "INTEGER"),
		new DataField("GPS_MISSING_SV", "TINYINT"),
		new DataField("GPS_RAW_DATA", "BINARY"),
		new DataField("CURRENT_DATA_BUFFER_SIZE", "INTEGER"),
		new DataField("OLD_DATA_BUFFER_SIZE", "INTEGER")};
	
	private Map<Long,SvContainer> newSvBuffer = Collections.synchronizedMap(new HashMap<Long,SvContainer>());
	private Map<Long,SvContainer> oldSvBuffer = Collections.synchronizedMap(new HashMap<Long,SvContainer>());
	private long bufferSizeInMs;
	
	private enum Buf {
		NEW_BUF,
		OLD_BUF
	}

	private Timer emptyBufferTimer = new Timer();
	
	@Override
	public boolean initialize() {
		boolean ret = super.initialize();
		
		String bufferSizeInDays = getVirtualSensorConfiguration().getMainClassInitialParams().get("buffer_size_in_days");
		try {
			bufferSizeInMs = Long.decode(bufferSizeInDays) * 86400000L;
		}
		catch (NumberFormatException e) {
			logger.error("buffer_size_in_days has to be an integer");
			return false;
		}
		
		return ret;
	}
	
	@Override
	public void dataAvailable(String inputStreamName, StreamElement data) {
		if ((Long)data.getData(GPS_TIME_FIELD_NAME) > System.currentTimeMillis()-bufferSizeInMs) {
			processData(inputStreamName, data, System.currentTimeMillis(), newSvBuffer, Buf.NEW_BUF);
		}
		else {
			updateTimer();
			processData(inputStreamName, data, (Long) data.getData(dataField[1].getName()), oldSvBuffer, Buf.OLD_BUF);
		}
	}
	
	private void processData(String inputStreamName, StreamElement data, Long refTime, Map<Long,SvContainer> svContainerMap, Buf buf) {
		Long gps_unixtime = (Long)data.getData(GPS_TIME_FIELD_NAME);

		SvContainer svContainer = svContainerMap.get(gps_unixtime);
		try {
			if (svContainer == null) {
				svContainer = new SvContainer(inputStreamName, (Byte)data.getData(GPS_NUMSV_FIELD_NAME));
			}
			
			if (svContainer.putSv(data)) {
				data = svContainer.getRawBinaryStream();
				svContainerMap.remove(gps_unixtime);
				data.setData(dataField[10].getName(), newSvBuffer.size());
				data.setData(dataField[11].getName(), oldSvBuffer.size());
				super.dataAvailable(svContainer.getInputStreamName(), data);
				
			}
			else {
				svContainerMap.put(gps_unixtime, svContainer);
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		
		if (refTime != null) {
			Iterator<Long> iter = svContainerMap.keySet().iterator();
			while (iter.hasNext()) {
				gps_unixtime = iter.next();
				if (gps_unixtime < refTime-bufferSizeInMs) {
					if (buf == Buf.NEW_BUF) {
						// put old streams into old buffer
						svContainer = svContainerMap.get(gps_unixtime);
						iter.remove();
						for (StreamElement se : svContainer.getStreamElements()) {
							processData(svContainer.getInputStreamName(), se, null, oldSvBuffer, Buf.OLD_BUF);
						}
					}
					else {
						// generate stream element out of really old streams
						SvContainer svc = svContainerMap.get(gps_unixtime);
						iter.remove();
						data = svc.getRawBinaryStream();
						data.setData(dataField[10].getName(), newSvBuffer.size());
						data.setData(dataField[11].getName(), oldSvBuffer.size());
						super.dataAvailable(svc.getInputStreamName(), data);
					}
				}
			}
		}
	}
	
	@Override
	public synchronized void dispose() {
		emptyBuffer(newSvBuffer);
		emptyBuffer(oldSvBuffer);
		emptyBufferTimer.cancel();
		super.dispose();
	}
	
	private void emptyBuffer(Map<Long,SvContainer> buffer) {
		Iterator<Long> iter = buffer.keySet().iterator();
		while (iter.hasNext()) {
			SvContainer svc = buffer.get(iter.next());
			iter.remove();
			StreamElement data = svc.getRawBinaryStream();
			data.setData(dataField[10].getName(), newSvBuffer.size());
			data.setData(dataField[11].getName(), oldSvBuffer.size());
			super.dataAvailable(svc.getInputStreamName(), data);
		}
	}

    public void updateTimer() {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
    			emptyBuffer(oldSvBuffer);
            }
        };
        emptyBufferTimer.cancel();
        emptyBufferTimer = new Timer();
        emptyBufferTimer.schedule(timerTask, bufferSizeInMs);
    }

	class SvContainer {
		private ArrayList<StreamElement> streamElements;
		private String inputStreamName;
		private Byte numSv;
		
		protected SvContainer(String inputStreamName, Byte numSv) throws Exception {
			if (numSv <= 0 || numSv > Byte.MAX_VALUE)
				throw new Exception("numSv out of range: " + numSv);
			this.inputStreamName = inputStreamName;
			this.numSv = numSv;
			streamElements = new ArrayList<StreamElement>(numSv);
		}
		
		public ArrayList<StreamElement> getStreamElements() {
			return streamElements;
		}

		protected boolean putSv(StreamElement streamElement) throws Exception {
			if (streamElements.size() == numSv)
				throw new Exception("SvContainer already full!");
			streamElements.add(streamElement);
			if (streamElements.size() == numSv)
				return true;
			else
				return false;
		}
		
		protected String getInputStreamName() {
			return inputStreamName;
		}
		
		protected Byte getNumSv() {
			return numSv;
		}
		
		protected StreamElement getRawBinaryStream() {
			ByteBuffer rxmRaw = ByteBuffer.allocate(16+24*streamElements.size());
			rxmRaw.order(ByteOrder.LITTLE_ENDIAN);
			
			// RXM-RAW Header
			rxmRaw.put((byte) 0xB5);
			rxmRaw.put((byte) 0x62);
			
			// RXM-RAW ID
			rxmRaw.put((byte) 0x02);
			rxmRaw.put((byte) 0x10);
			
			// RXM-RAW Length
			rxmRaw.putShort((short) (24*streamElements.size()));
			
			// RXM-RAW Payload
			rxmRaw.putInt((Integer)streamElements.get(0).getData(GPS_ITOW_FIELD_NAME));
			rxmRaw.putShort((Short)streamElements.get(0).getData(GPS_WEEK_FIELD_NAME));
			rxmRaw.put((byte) (streamElements.size() & 0xFF));
			rxmRaw.put((byte) 0x00);
			for (StreamElement se : streamElements) {
				rxmRaw.putDouble((Double)se.getData(GPS_CPMES_FIELD_NAME));
				rxmRaw.putDouble((Double)se.getData(GPS_PRMES_FIELD_NAME));
				double d = (Double)se.getData(GPS_DOMES_FIELD_NAME);
				rxmRaw.putFloat((float)d);
				rxmRaw.put((Byte)se.getData(GPS_SV_FIELD_NAME));
				rxmRaw.put((byte)((Short)se.getData(GPS_MESQI_FIELD_NAME)&0xFF));
				rxmRaw.put((Byte)se.getData(GPS_CNO_FIELD_NAME));
				rxmRaw.put((Byte)se.getData(GPS_LLI_FIELD_NAME));
			}
			
			// RXM-RAW Checksum
			byte CK_A = 0;
			byte CK_B = 0;
			for (int i=2; i<14+24*streamElements.size(); i++) {
				CK_A += rxmRaw.get(i);
				CK_B += CK_A;
			}
			rxmRaw.put(CK_A);
			rxmRaw.put(CK_B);
			
			return new StreamElement(dataField, new Serializable[]{
					streamElements.get(0).getData(dataField[0].getName()),
					streamElements.get(0).getData(dataField[1].getName()),
					streamElements.get(0).getData(dataField[2].getName()),
					streamElements.get(0).getData(dataField[3].getName()),
					streamElements.get(0).getData(dataField[4].getName()),
					streamElements.get(0).getData(dataField[5].getName()),
					GPS_RAW_DATA_VERSION,
					(int)streamElements.size(),
					(byte)(numSv-streamElements.size()),
					rxmRaw.array(),
					null,
					null});
		}
	}
}
