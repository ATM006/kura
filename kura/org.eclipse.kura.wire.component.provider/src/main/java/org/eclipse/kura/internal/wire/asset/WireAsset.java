/*******************************************************************************
 * Copyright (c) 2016, 2017 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Eurotech
 *  Amit Kumar Mondal
 *  
 *******************************************************************************/
package org.eclipse.kura.internal.wire.asset;

import static java.util.Objects.requireNonNull;
import static org.eclipse.kura.asset.ChannelType.READ;
import static org.eclipse.kura.asset.ChannelType.READ_WRITE;
import static org.eclipse.kura.asset.ChannelType.WRITE;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.asset.Asset;
import org.eclipse.kura.asset.AssetFlag;
import org.eclipse.kura.asset.AssetRecord;
import org.eclipse.kura.asset.AssetStatus;
import org.eclipse.kura.asset.Channel;
import org.eclipse.kura.asset.provider.BaseAsset;
import org.eclipse.kura.localization.LocalizationAdapter;
import org.eclipse.kura.localization.resources.WireMessages;
import org.eclipse.kura.type.ErrorValue;
import org.eclipse.kura.type.TypedValue;
import org.eclipse.kura.type.TypedValues;
import org.eclipse.kura.util.base.ThrowableUtil;
import org.eclipse.kura.util.collection.CollectionUtil;
import org.eclipse.kura.wire.WireEmitter;
import org.eclipse.kura.wire.WireEnvelope;
import org.eclipse.kura.wire.WireField;
import org.eclipse.kura.wire.WireHelperService;
import org.eclipse.kura.wire.WireReceiver;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.wireadmin.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class WireAsset is a wire component which provides all necessary higher
 * level abstractions of a Kura asset. This wire asset is an integral wire
 * component in Kura Wires topology as it represents an industrial device with a
 * field protocol driver associated to it.<br/>
 * <br/>
 *
 * The WireRecord to be emitted by every wire asset comprises the following keys
 *
 * <ul>
 * <li>channel_name</li>
 * <li>asset_flag</li>
 * <li>timestamp</li>
 * <li>typed_value</li>
 * <li>exception</li> (This Wire Field is present if and only if asset_flag is
 * set to FAILURE)
 * </ul>
 *
 * <br/>
 * Also note that, if the channel name is equal to the received value of the
 * channel wire field name, then it would be considered as a WRITE wire field
 * value to the specific channel. <br/>
 * <br/>
 * For instance, {@code A} asset sends a Wire Record to {@code B} asset and the
 * received Wire Record contains list of Wire Fields. If there exists a Wire
 * Field which signifies the channel name and if this channel name also exists
 * in {@code B}'s list of configured channels, then the Wire Field which
 * contains the typed value of this channel in the received Wire Record will be
 * considered as a WRITE Value in that specific channel in B and this value will
 * be written to {@code B}'s channel
 *
 * @see Asset
 */
public final class WireAsset extends BaseAsset implements WireEmitter, WireReceiver {

    /** Configuration PID Property. */
    private static final String CONF_PID = "org.eclipse.kura.wire.WireAsset";

    private static final Logger logger = LoggerFactory.getLogger(WireAsset.class);

    private static final WireMessages message = LocalizationAdapter.adapt(WireMessages.class);

    private volatile WireHelperService wireHelperService;

    private WireSupport wireSupport;

    /**
     * Binds the Wire Helper Service.
     *
     * @param wireHelperService
     *            the new Wire Helper Service
     */
    public synchronized void bindWireHelperService(final WireHelperService wireHelperService) {
        if (this.wireHelperService == null) {
            this.wireHelperService = wireHelperService;
        }
    }

    /**
     * Unbinds the Wire Helper Service.
     *
     * @param wireHelperService
     *            the new Wire Helper Service
     */
    public synchronized void unbindWireHelperService(final WireHelperService wireHelperService) {
        if (this.wireHelperService == wireHelperService) {
            this.wireHelperService = null;
        }
    }

    /**
     * OSGi service component callback while activation.
     *
     * @param componentContext
     *            the component context
     * @param properties
     *            the service properties
     */
    @Override
    protected synchronized void activate(final ComponentContext componentContext,
            final Map<String, Object> properties) {
        logger.debug(message.activatingWireAsset());
        super.activate(componentContext, properties);
        this.wireSupport = this.wireHelperService.newWireSupport(this);
        logger.debug(message.activatingWireAssetDone());
    }

    /**
     * OSGi service component callback while updation.
     *
     * @param properties
     *            the service properties
     */
    @Override
    public synchronized void updated(final Map<String, Object> properties) {
        logger.debug(message.updatingWireAsset());
        super.updated(properties);
        logger.debug(message.updatingWireAssetDone());
    }

    /**
     * OSGi service component callback while deactivation.
     *
     * @param context
     *            the context
     */
    @Override
    protected synchronized void deactivate(final ComponentContext context) {
        logger.debug(message.deactivatingWireAsset());
        super.deactivate(context);
        logger.debug(message.deactivatingWireAssetDone());
    }

    /** {@inheritDoc} */
    @Override
    public void consumersConnected(final Wire[] wires) {
        this.wireSupport.consumersConnected(wires);
    }

    /** {@inheritDoc} */
    @Override
    protected String getFactoryPid() {
        return CONF_PID;
    }

    /**
     * This method is triggered as soon as the wire component receives a Wire
     * Envelope. After it receives a Wire Envelope, it checks for all associated
     * channels to read and write and perform the operations accordingly. The
     * order of executions are performed the following way:
     *
     * <ul>
     * <li>Perform all read operations on associated reading channels</li>
     * <li>Perform all write operations on associated writing channels</li>
     * <ul>
     *
     * Both of the aforementioned operations are performed as soon as it timer
     * wire component is also triggered.
     *
     * @param wireEnvelope
     *            the received wire envelope
     * @throws NullPointerException
     *             if Wire Envelope is null
     */
    @Override
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, message.wireEnvelopeNonNull());
        logger.debug(message.wireEnvelopeReceived() + this.wireSupport);

        // filtering list of wire records based on the provided severity level
        final WireRecord record = wireEnvelope.getRecord();
        final List<Long> channelIds = determineReadingChannels();
        final List<AssetRecord> assetRecordsToWriteChannels = determineWritingChannels(record);

        // perform the operations
        writeChannels(assetRecordsToWriteChannels);
        readChannels(channelIds);
    }

    /**
     * Determines the channels to read from the list of provided Wire Records
     *
     * @param records
     *            the list of Wire Records
     * @return the list of channel IDs
     * @throws NullPointerException
     *             if argument is null
     * @throws IllegalArgumentException
     *             if argument is empty
     */
    private List<Long> determineReadingChannels() {

        final List<Long> channelsToRead = CollectionUtil.newArrayList();
        final Map<Long, Channel> channels = this.assetConfiguration.getAssetChannels();
        for (final Map.Entry<Long, Channel> channelEntry : channels.entrySet()) {
            final Channel channel = channelEntry.getValue();
            if (channel.getType() == READ || channel.getType() == READ_WRITE) {
                channelsToRead.add(channel.getId());
            }
        }
        return channelsToRead;
    }

    /**
     * Determine the channels to write
     *
     * @param records
     *            the list of Wire Records to parse
     * @return list of Asset Records containing the values to be written
     * @throws NullPointerException
     *             if argument is null
     * @throws IllegalArgumentException
     *             if argument is empty
     */
    private List<AssetRecord> determineWritingChannels(final WireRecord record) {
        requireNonNull(record, message.wireRecordsNonNull());

        final List<AssetRecord> assetRecordsToWriteChannels = CollectionUtil.newArrayList();
        final Map<Long, Channel> channels = this.assetConfiguration.getAssetChannels();
        for (final WireField wireField : record.getFields()) {
            String channelNameWireField = null;

            for (final Map.Entry<Long, Channel> channelEntry : channels.entrySet()) {
                final Channel channel = channelEntry.getValue();
                if (channel.getType() == WRITE || channel.getType() == READ_WRITE) {
                    final String wireFieldName = wireField.getName();
                    final TypedValue<?> value = wireField.getValue();
                    if (value instanceof ErrorValue) {
                        logger.info("Received error in input");
                        break;
                    }
                    if (wireFieldName.equalsIgnoreCase(channelNameWireField)
                            && channel.getValueType() == value.getType()) {
                        assetRecordsToWriteChannels.add(prepareAssetRecord(channel, wireField.getValue()));
                    }
                }
            }

        }
        return assetRecordsToWriteChannels;
    }

    /**
     * Perform Channel Read and Emit operations
     *
     * @param channelsToRead
     *            the list of {@link Channel} IDs
     * @throws NullPointerException
     *             if the provided list is null
     */
    private void readChannels(final List<Long> channelsToRead) {
        requireNonNull(channelsToRead, message.channelIdsNonNull());
        try {
            List<AssetRecord> recentlyReadRecords = null;
            if (!channelsToRead.isEmpty()) {
                recentlyReadRecords = read(channelsToRead);
            }
            if (recentlyReadRecords != null) {
                emitAssetRecords(recentlyReadRecords);
            }
        } catch (final KuraException e) {
            logger.error(message.errorPerformingRead() + ThrowableUtil.stackTraceAsString(e));
        }
    }

    /**
     * Create an asset record from the provided channel information.
     *
     * @param channel
     *            the channel to get the values from
     * @param value
     *            the value
     * @return the asset record
     * @throws NullPointerException
     *             if any of the provided arguments is null
     */
    private AssetRecord prepareAssetRecord(final Channel channel, final TypedValue<?> value) {
        requireNonNull(channel, message.channelNonNull());
        requireNonNull(value, message.valueNonNull());

        final AssetRecord assetRecord = new AssetRecord(channel.getId());
        assetRecord.setValue(value);
        return assetRecord;
    }

    /**
     * Emit the provided list of asset records to the associated wires.
     *
     * @param assetRecords
     *            the list of asset records conforming to the aforementioned
     *            specification
     * @throws NullPointerException
     *             if provided records list is null
     * @throws IllegalArgumentException
     *             if provided records list is empty
     */
    private void emitAssetRecords(final List<AssetRecord> assetRecords) {
        requireNonNull(assetRecords, message.assetRecordsNonNull());
        if (assetRecords.isEmpty()) {
            throw new IllegalArgumentException(message.assetRecordsNonEmpty());
        }

        final WireRecord wireRecord = new WireRecord(new Timestamp(new Date().getTime())); // TODO: manage position
        for (final AssetRecord assetRecord : assetRecords) {
            final AssetStatus assetStatus = assetRecord.getAssetStatus();
            final AssetFlag assetFlag = assetStatus.getAssetFlag();
            final long channelId = assetRecord.getChannelId();
            final String channelName = this.assetConfiguration.getAssetChannels().get(channelId).getName();

            final TypedValue<?> typedValue;
            if (assetFlag == AssetFlag.FAILURE) {
                String errorMessage = "ERROR NOT SPECIFIED";
                final Exception exception = assetStatus.getException();
                final String exceptionMsg = assetStatus.getExceptionMessage();
                if (exception != null && exceptionMsg != null) {
                    errorMessage = exceptionMsg + " " + ThrowableUtil.stackTraceAsString(exception);
                } else if (exception == null && exceptionMsg != null) {
                    errorMessage = exceptionMsg;
                } else if (exception != null && exceptionMsg == null) {
                    errorMessage = ThrowableUtil.stackTraceAsString(exception);
                }
                typedValue = new ErrorValue(errorMessage);
            } else {
                typedValue = assetRecord.getValue();
            }

            WireField wireField = new WireField(channelName, typedValue);

            try {
                wireField.addProperty("assetName", TypedValues.newStringValue(getConfiguration().getPid()));
            } catch (final KuraException e) {
                logger.error(ThrowableUtil.stackTraceAsString(e));
            }

            wireField.addProperty("channelId", TypedValues.newLongValue(channelId));
            wireField.addProperty("assetFlag", TypedValues.newStringValue(assetFlag.name()));
            wireField.addProperty("timestamp", TypedValues.newLongValue(assetRecord.getTimestamp()));

            wireRecord.addField(wireField);
        }
        this.wireSupport.emit(wireRecord);
    }

    /**
     * Perform Channel Write operation
     *
     * @param assetRecordsToWriteChannels
     *            the list of {@link AssetRecord}s
     * @throws NullPointerException
     *             if the provided list is null
     */
    private void writeChannels(final List<AssetRecord> assetRecordsToWriteChannels) {
        requireNonNull(assetRecordsToWriteChannels, message.assetRecordsNonNull());
        try {
            write(assetRecordsToWriteChannels);
        } catch (final KuraException e) {
            logger.error(message.errorPerformingWrite() + ThrowableUtil.stackTraceAsString(e));
        }
    }

    /** {@inheritDoc} */
    @Override
    public Object polled(final Wire wire) {
        return this.wireSupport.polled(wire);
    }

    /** {@inheritDoc} */
    @Override
    public void producersConnected(final Wire[] wires) {
        this.wireSupport.producersConnected(wires);
    }

    /** {@inheritDoc} */
    @Override
    public void updated(final Wire wire, final Object value) {
        this.wireSupport.updated(wire, value);
    }
}
