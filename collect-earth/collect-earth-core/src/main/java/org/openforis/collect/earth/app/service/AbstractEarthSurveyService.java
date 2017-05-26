package org.openforis.collect.earth.app.service;

import static org.openforis.collect.earth.app.EarthConstants.ACTIVELY_SAVED_ATTRIBUTE_NAME;
import static org.openforis.collect.earth.app.EarthConstants.ACTIVELY_SAVED_ON_ATTRIBUTE_NAME;
import static org.openforis.collect.earth.app.EarthConstants.ACTIVELY_SAVED_ON_PARAMETER;
import static org.openforis.collect.earth.app.EarthConstants.ACTIVELY_SAVED_ON_PARAMETER_OLD;
import static org.openforis.collect.earth.app.EarthConstants.ACTIVELY_SAVED_PARAMETER;
import static org.openforis.collect.earth.app.EarthConstants.COLLECT_REASON_BLANK_NOT_SPECIFIED_MESSAGE;
import static org.openforis.collect.earth.app.EarthConstants.OPERATOR_PARAMETER;
import static org.openforis.collect.earth.app.EarthConstants.PLACEMARK_FOUND_PARAMETER;
import static org.openforis.collect.earth.app.EarthConstants.ROOT_ENTITY_NAME;
import static org.openforis.collect.earth.app.EarthConstants.SKIP_FILLED_PLOT_PARAMETER;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JOptionPane;

import org.openforis.collect.earth.CollectEarthSurveyContext;
import org.openforis.collect.earth.app.EarthConstants;
import org.openforis.collect.earth.app.view.Messages;
import org.openforis.collect.earth.core.handlers.BalloonInputFieldsUtils;
import org.openforis.collect.earth.core.handlers.DateAttributeHandler;
import org.openforis.collect.earth.core.model.PlacemarkInputFieldInfo;
import org.openforis.collect.earth.core.model.PlacemarkLoadResult;
import org.openforis.collect.manager.RecordManager;
import org.openforis.collect.manager.SurveyManager;
import org.openforis.collect.model.CollectRecord;
import org.openforis.collect.model.CollectRecord.Step;
import org.openforis.collect.model.CollectSurvey;
import org.openforis.collect.model.NodeChangeSet;
import org.openforis.collect.model.RecordUpdater;
import org.openforis.collect.model.RecordValidationReportGenerator;
import org.openforis.collect.model.RecordValidationReportItem;
import org.openforis.collect.model.validation.CollectEarthValidator;
import org.openforis.collect.persistence.RecordPersistenceException;
import org.openforis.idm.metamodel.AttributeDefinition;
import org.openforis.idm.metamodel.EntityDefinition;
import org.openforis.idm.metamodel.ModelVersion;
import org.openforis.idm.metamodel.NodeLabel.Type;
import org.openforis.idm.metamodel.Schema;
import org.openforis.idm.metamodel.SurveyContext;
import org.openforis.idm.model.Attribute;
import org.openforis.idm.model.BooleanAttribute;
import org.openforis.idm.model.BooleanValue;
import org.openforis.idm.model.DateAttribute;
import org.openforis.idm.model.Entity;
import org.openforis.idm.model.Node;
import org.openforis.idm.model.TextAttribute;
import org.openforis.idm.model.TextValue;
import org.openforis.idm.model.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AbstractEarthSurveyService {

	protected final Logger logger = LoggerFactory.getLogger(this.getClass());

	private static final String PREVIEW_PLACEMARK_ID = "testPlacemark";
	private static final String PREVIEW_PLACEMARK_ID_PLACEHOLDER = "$[EXTRA_id]";

	@Autowired
	protected LocalPropertiesService localPropertiesService;
	@Autowired
	protected RecordManager recordManager;
	@Autowired
	protected SurveyManager surveyManager;
	@Autowired
	protected CollectEarthValidator collectEarthValidator;
	
	protected CollectSurvey collectSurvey;
	protected RecordUpdater recordUpdater;
	protected BalloonInputFieldsUtils collectParametersHandler;
	protected Map<RecordKey, CollectRecord> recordCache;

	public AbstractEarthSurveyService() {
		collectParametersHandler = new BalloonInputFieldsUtils();
		recordUpdater = new RecordUpdater();
		recordUpdater.setClearDependentCodeAttributes(true);
		recordUpdater.setClearNotRelevantAttributes(true);
		recordCache = Collections.synchronizedMap(new RecordCache());
	}

	private void addLocalProperties(Map<String, String> placemarkParameters) {
		placemarkParameters.put(SKIP_FILLED_PLOT_PARAMETER,
				Boolean.toString(localPropertiesService.shouldJumpToNextPlot())); // $NON-NLS-1$
	}

	private void addResultParameter(Map<String, String> placemarkParameters, boolean found) {
		placemarkParameters.put(PLACEMARK_FOUND_PARAMETER, Boolean.toString(found)); // $NON-NLS-1$
	}

	@Deprecated
	private void addValidationMessages(Map<String, String> parameters, CollectRecord record) {
		// Validation
		recordUpdater.validate(record);

		final RecordValidationReportGenerator reportGenerator = new RecordValidationReportGenerator(record);
		final List<RecordValidationReportItem> validationItems = reportGenerator.generateValidationItems();

		for (final RecordValidationReportItem recordValidationReportItem : validationItems) {
			String label = ""; //$NON-NLS-1$
			if (recordValidationReportItem.getNodeId() != null) {
				final Node<?> node = record.getNodeByInternalId(recordValidationReportItem.getNodeId());
				label = node.getDefinition().getLabel(Type.INSTANCE,
						localPropertiesService.getUiLanguage().name().toLowerCase());

				String message = getAdaptedValidationMessage(recordValidationReportItem.getMessage());

				parameters.put("validation_" + node.getDefinition().getName(), label + " - " + message); //$NON-NLS-1$ //$NON-NLS-2$

			} else {
				label = recordValidationReportItem.getPath();
				parameters.put("validation_" + label, label + " - " + recordValidationReportItem.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}

		}

		if (validationItems.size() > 0) {
			parameters.put("valid_data", "false"); //$NON-NLS-1$ //$NON-NLS-2$
		}

	}

	protected String getAdaptedValidationMessage(String validationMessage) {
		String message;
		if (validationMessage.equals(COLLECT_REASON_BLANK_NOT_SPECIFIED_MESSAGE)) { // $NON-NLS-1$
			message = Messages.getString("EarthSurveyService.9"); //$NON-NLS-1$
		} else {
			message = validationMessage;
		}
		return message;
	}

	private CollectRecord createRecord() throws RecordPersistenceException {
		String modelVersionName = localPropertiesService.getModelVersionName();
		CollectRecord record = recordManager.create(getCollectSurvey(),
				getRootEntityDefinition(), null, modelVersionName, null);
		return record;
	}

	public EntityDefinition getRootEntityDefinition() {
		Schema schema = getCollectSurvey().getSchema();
		EntityDefinition rootEntityDef = schema.getRootEntityDefinition(ROOT_ENTITY_NAME);
		return rootEntityDef;
	}

	public CollectSurvey getCollectSurvey() {
		return collectSurvey;
	}

	protected String getIdmFilePath() {
		return localPropertiesService.getImdFile();
	}

	public Map<String, String> getPlacemark(String[] keyAttributes, boolean validateRecord) {
		CollectRecord record = getOrLoadRecord(keyAttributes, validateRecord);
		Map<String, String> placemarkParameters = null;
		if (record == null) {
			placemarkParameters = new HashMap<String, String>();
			addResultParameter(placemarkParameters, false);
		} else {
			placemarkParameters = collectParametersHandler.getValuesByHtmlParameters(record.getRootEntity());

			if ((placemarkParameters.get("collect_code_canopy_cover") != null) //$NON-NLS-1$
					&& placemarkParameters.get("collect_code_canopy_cover").equals("0")) { //$NON-NLS-1$ //$NON-NLS-2$
				placemarkParameters.put("collect_code_canopy_cover", //$NON-NLS-1$
						"0;collect_code_deforestation_reason=" //$NON-NLS-1$
								+ placemarkParameters.get("collect_code_deforestation_reason")); //$NON-NLS-1$
			}
			// For the PNG version with the old name
			if ((placemarkParameters.get("collect_code_crown_cover") != null) //$NON-NLS-1$
					&& placemarkParameters.get("collect_code_crown_cover").equals("0")) { //$NON-NLS-1$ //$NON-NLS-2$
				placemarkParameters.put("collect_code_crown_cover", //$NON-NLS-1$
						"0;collect_code_deforestation_reason=" //$NON-NLS-1$
								+ placemarkParameters.get("collect_code_deforestation_reason")); //$NON-NLS-1$
			}
			addResultParameter(placemarkParameters, true);
		}
		addLocalProperties(placemarkParameters);

		return placemarkParameters;
	}

	public PlacemarkLoadResult loadPlacemarkExpanded(String[] keyAttributeValues) {
		adjustTestPlacemarkId(keyAttributeValues);
		
		CollectRecord record = getOrLoadRecord(keyAttributeValues);
		if (record == null) {
			PlacemarkLoadResult result = new PlacemarkLoadResult();
			result.setSuccess(false);
			result.setMessage("No placemark found");
			return result;
		} else {
			RecordKey recordKey = new RecordKey(getCollectSurvey(), keyAttributeValues);
			recordCache.put(recordKey, record);
			return createPlacemarkLoadSuccessResult(record);
		}
	}

	public CollectRecord getOrLoadRecord(String[] mulitpleKeyAttributes) {
		return getOrLoadRecord(mulitpleKeyAttributes, true);
	}
	
	private void adjustTestPlacemarkId(String[] keyValues) {
		if (isPreviewRecordID(keyValues)) {
			keyValues[0] = PREVIEW_PLACEMARK_ID;
		}
	}

	public synchronized CollectRecord getOrLoadRecord(String[] mulitpleKeyAttributes, boolean validateRecord) {
		CollectRecord cachedRecord = recordCache.get(new RecordKey(getCollectSurvey(), mulitpleKeyAttributes));
		if (cachedRecord != null) {
			return cachedRecord;
		} else {
			List<CollectRecord> summaries = recordManager.loadSummaries(getCollectSurvey(), ROOT_ENTITY_NAME,
					mulitpleKeyAttributes);
			if (summaries.isEmpty()) {
				return null;
			} else {
				CollectRecord summary = summaries.get(0);
				return recordManager.load(getCollectSurvey(), summary.getId(), Step.ENTRY, validateRecord);
			}
		}
	}

	/**
	 * Return a list of the Collect records that have been updated since the
	 * time passed as an argument. This method is useful to update the status of
	 * the placemarks ( updating the icon on the KML using a NetworkLink )
	 * 
	 * @param updatedSince
	 *            The date from which we want to find out if there were any
	 *            records that were updates/added
	 * @return The list of record that have been updated since the time stated
	 *         in updatedSince
	 */
	public List<CollectRecord> getRecordSummariesSavedSince(Date updatedSince) {
		List<CollectRecord> summaries = recordManager.loadSummaries(getCollectSurvey(), ROOT_ENTITY_NAME, updatedSince);
		return summaries;
	}

	public String[] getPlacemarksId(List<CollectRecord> listOfRecords) {
		if (listOfRecords == null) {
			return new String[0];
		}
		final String[] placemarkIds = new String[listOfRecords.size()];
		for (int i = 0; i < listOfRecords.size(); i++) {
			CollectRecord recordSummary = listOfRecords.get(i);
			List<String> rootEntityKeyValues = recordSummary.getRootEntityKeyValues();
			String keyValues = "";
			for (String key : rootEntityKeyValues) {
				keyValues += key + ",";
			}
			keyValues = keyValues.substring(0, keyValues.lastIndexOf(','));

			placemarkIds[i] = keyValues;
		}

		return placemarkIds;
	}

	/**
	 * Return the list of the IDs of records that are already saved (completely
	 * or partially) in the database.
	 * 
	 * @return The list of the IDs of the records for the survey in the DB
	 */
	public String[] getRecordsSavedIDs() {
		final List<CollectRecord> recordsSavedForSurvey = recordManager.loadSummaries(getCollectSurvey(),
				ROOT_ENTITY_NAME);

		if ((recordsSavedForSurvey != null) && !recordsSavedForSurvey.isEmpty()) {
			return getPlacemarksId(recordsSavedForSurvey);
		} else {
			return null;
		}
	}

	protected void checkVersions(CollectSurvey loadedCollectSurvey) {

		if (!loadedCollectSurvey.getVersions().isEmpty()) {
			if (loadedCollectSurvey.getVersions().size() == 1) {
				ModelVersion onlyModelVersion = loadedCollectSurvey.getVersions().get(0);
				localPropertiesService.setModelVersionName(onlyModelVersion.getName());
			}

			/*
			 * The model version name comes directly from the CEP file ( initial
			 * earth properties )
			 */
			else {

				// Choose one of the versions
				ModelVersion chosenVersion = (ModelVersion) JOptionPane.showInputDialog(null,
						"Choose one survey version to work with", "Choose version", JOptionPane.QUESTION_MESSAGE, null,
						loadedCollectSurvey.getVersions().toArray(),
						loadedCollectSurvey.getVersions().get(loadedCollectSurvey.getVersions().size() - 1));
				localPropertiesService.setModelVersionName(chosenVersion.getName());

			}
		}
	}

	public boolean isPlacemarkEdited(Map<String, String> parameters) {
		return parameters != null && "false".equals(parameters.get(ACTIVELY_SAVED_PARAMETER)); //$NON-NLS-1$
	}

	public boolean isPlacemarkSavedActively(Map<String, String> parameters) {
		return parameters != null && "true".equals(parameters.get(ACTIVELY_SAVED_PARAMETER)); //$NON-NLS-1$
	}

	public void setCollectSurvey(CollectSurvey collectSurvey) {
		this.collectSurvey = collectSurvey;
		this.collectSurvey.setSurveyContext(createCollectEarthSurveyContext(collectSurvey.getContext()));
	}

	private CollectEarthSurveyContext createCollectEarthSurveyContext(SurveyContext collectSurveyContext) {
		CollectEarthSurveyContext collectEarthSurveyContext = new CollectEarthSurveyContext(collectSurveyContext.getExpressionFactory(), 
				collectEarthValidator, collectSurveyContext.getCodeListService());
		return collectEarthSurveyContext;
	}

	private void setPlacemarkSavedOn(CollectRecord record) {
		String path = ROOT_ENTITY_NAME + "/" + ACTIVELY_SAVED_ON_ATTRIBUTE_NAME;
		Attribute<?, ?> attr = record.findNodeByPath(path);
		if (attr == null) {
			logger.warn("The expected attribute at " + path + " could not be found!");
		} else {
			if (attr instanceof DateAttribute) {
				org.openforis.idm.model.Date date = org.openforis.idm.model.Date.parse(new Date());
				recordUpdater.updateAttribute((DateAttribute) attr, date);
			} else if (attr instanceof TextAttribute) {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm");
				org.openforis.idm.model.Date date = org.openforis.idm.model.Date.parse(new Date());
				recordUpdater.updateAttribute((TextAttribute) attr, new TextValue(sdf.format(date)));
			} else {
				logger.error("Attribute " + path + " is expected to be of type Text or Date");
			}
		}
	}

	private void setPlacemarkSavedActively(CollectRecord record, boolean value) {
		BooleanAttribute attr = record.findNodeByPath(ROOT_ENTITY_NAME + "/" + ACTIVELY_SAVED_ATTRIBUTE_NAME);
		recordUpdater.updateAttribute(attr, new BooleanValue(value));
	}
	
	private void setPlacemarkSavedOn(Map<String, String> parameters) {
		String dateSaved = new SimpleDateFormat( DateAttributeHandler.DATE_ATTRIBUTE_FORMAT ).format(new Date());

		parameters.put(ACTIVELY_SAVED_ON_PARAMETER, dateSaved);
		parameters.put(ACTIVELY_SAVED_ON_PARAMETER_OLD, dateSaved);
	}

	private void setPlacemarkSavedActively(Map<String, String> parameters, boolean value) {
		parameters.put(ACTIVELY_SAVED_PARAMETER, Boolean.toString(value)); // $NON-NLS-1$
	}

	@Deprecated
	public synchronized boolean storePlacemarkOld(Map<String, String> parameters) {

		String[] keys = new String[] { parameters.get(EarthConstants.PLACEMARK_ID_PARAMETER) };

		final List<CollectRecord> summaries = recordManager.loadSummaries(getCollectSurvey(), ROOT_ENTITY_NAME, keys); // $NON-NLS-1$
		boolean success = false;

		try {
			// Add the operator to the collected data
			parameters.put(OPERATOR_PARAMETER, localPropertiesService.getOperator());

			CollectRecord record = null;
			Entity plotEntity = null;

			if (summaries.size() > 0) { // DELETE IF ALREADY PRESENT
				record = summaries.get(0);
				recordManager.delete(record.getId());
				record = createRecord();
				plotEntity = record.getRootEntity();
			} else {
				// Create new record
				record = createRecord();
				plotEntity = record.getRootEntity();
				logger.warn("Creating a new plot entity with data " + parameters.toString()); //$NON-NLS-1$
			}

			boolean userClickOnSaveAndValidate = isPlacemarkSavedActively(parameters);

			setPlacemarkSavedOn(parameters);

			// Populate the data of the record using the HTTP parameters
			// received
			// This also generates the validation messages
			collectParametersHandler.saveToEntity(parameters, plotEntity);

			// Do not validate unless actively saved
			if (userClickOnSaveAndValidate) {
				addValidationMessages(parameters, record);

				// Check that there is no validation errors so the tick doesn't
				// turn green
				if (record.getSkipped() != 0 || record.getErrors() != 0) {
					setPlacemarkSavedActively(parameters, false);
					// Force saving again to remove the "actively saved"
					// parameter!
					collectParametersHandler.saveToEntity(parameters, plotEntity);
				}
			}

			record.setModifiedDate(new Date());
			recordManager.save(record);

			success = true;
		} catch (Exception e) {
			logger.error("Error while storing the record " + e.getMessage(), e); //$NON-NLS-1$
		}
		return success;
	}

	public synchronized PlacemarkLoadResult updatePlacemarkData(String[] plotKeyAttributes,
			Map<String, String> parameters, String sessionId, boolean partialUpdate) {
		try {
			adjustTestPlacemarkId(plotKeyAttributes);
			
			// Add the operator to the collected data
			parameters.put(OPERATOR_PARAMETER, localPropertiesService.getOperator());

			boolean preview = isPreviewRecordID(plotKeyAttributes);
			
			CollectRecord record = getOrLoadRecord(plotKeyAttributes);
			boolean newRecord = record == null;
			if (newRecord) {
				record = createRecord();
				newRecord = true;
				
				collectParametersHandler.saveToEntity(parameters, record.getRootEntity(), true);

				// update actively_saved_on attribute now, otherwise if it's empty
				// it counts as an error
				setPlacemarkSavedOn(record);
				setPlacemarkSavedActively(record, false);
				
				updateKeyAttributeValues(record, plotKeyAttributes);
				record.setModifiedDate(new Date());
				if (! preview) {
					recordManager.save(record, sessionId);
				}
				recordCache.put(new RecordKey(getCollectSurvey(), plotKeyAttributes), record);

				return createPlacemarkLoadSuccessResult(record);
			} else {
				// Populate the data of the record using the HTTP parameters
				// received
				Entity plotEntity = record.getRootEntity();
				
				Map<String, String> oldPlacemarkParameters = collectParametersHandler
						.getValuesByHtmlParameters(record.getRootEntity());
				Map<String, String> changedParameters = calculateChanges(oldPlacemarkParameters, parameters);
				
				boolean placemarkAlreadySavedActively = isPlacemarkSavedActively(oldPlacemarkParameters);
				boolean userClickOnSubmitAndValidate = isPlacemarkSavedActively(parameters);
				
				NodeChangeSet changeSet = collectParametersHandler.saveToEntity(changedParameters, plotEntity);
				
				// update actively_saved_on attribute now, otherwise if it's empty
				// it counts as an error
				setPlacemarkSavedOn(record);
				
				boolean noErrors = record.getErrors() == 0 && record.getSkipped() == 0;
				
				if (userClickOnSubmitAndValidate && !noErrors) {
					// if the user clicks on submit and validate but the data is not
					// valid,
					// do not save the record as actively saved
					setPlacemarkSavedActively(record, false);
				}
				
				if (!preview && (!placemarkAlreadySavedActively || noErrors)) {
					// only save data if the information is completely valid or if
					// the record is not already completely saved (green)
					record.setModifiedDate(new Date());
					recordManager.save(record, sessionId);
				}
				if (partialUpdate) {
					return createPlacemarkLoadSuccessResult(record, changeSet);
				} else {
					return createPlacemarkLoadSuccessResult(record);
				}
			}
		} catch (Exception e) {
			logger.error("Error while storing the record " + e.getMessage(), e); //$NON-NLS-1$
			PlacemarkLoadResult result = new PlacemarkLoadResult();
			result.setSuccess(false);
			return result;
		}
	}

	private PlacemarkLoadResult createPlacemarkLoadSuccessResult(CollectRecord record) {
		return createPlacemarkLoadSuccessResult(record, null);
	}

	private PlacemarkLoadResult createPlacemarkLoadSuccessResult(CollectRecord record, NodeChangeSet changeSet) {
		PlacemarkLoadResult result = new PlacemarkLoadResult();
		result.setSuccess(true);
		result.setCollectRecord(record);
		result.setSkipFilled(localPropertiesService.shouldJumpToNextPlot());
		Map<String, PlacemarkInputFieldInfo> infoByParameterName = collectParametersHandler
				.extractFieldInfoByParameterName(record, changeSet,
						localPropertiesService.getUiLanguage().getLocale().getLanguage(),
						localPropertiesService.getModelVersionName());
		// adjust error messages
		for (Entry<String, PlacemarkInputFieldInfo> entry : infoByParameterName.entrySet()) {
			PlacemarkInputFieldInfo info = entry.getValue();
			if (info.isInError()) {
				String message = info.getErrorMessage();
				info.setErrorMessage(getAdaptedValidationMessage(message));
			}
		}
		result.setInputFieldInfoByParameterName(infoByParameterName);
		return result;
	}
	
	private void updateKeyAttributeValues(CollectRecord record, String[] keyAttributeValues) {
		List<AttributeDefinition> keyAttributeDefinitions = getCollectSurvey().getSchema().getRootEntityDefinitions()
				.get(0).getKeyAttributeDefinitions();
		for (int i = 0; i < keyAttributeValues.length; i++) {
			String keyValue = keyAttributeValues[i];
			AttributeDefinition keyAttrDef = keyAttributeDefinitions.get(i);
			Attribute<?, Value> keyAttr = record.findNodeByPath(keyAttrDef.getPath());
			Value keyVal;
			if (isPreviewRecordID(keyAttributeValues)) {
				keyVal = (Value) keyAttr.getDefinition().createValue(i == 0 ? PREVIEW_PLACEMARK_ID : "1");
			} else {
				keyVal = (Value) keyAttr.getDefinition().createValue(keyValue);
			}
			recordUpdater.updateAttribute(keyAttr, keyVal);
		}
	}

	private Map<String, String> calculateChanges(Map<String, String> oldPlacemarkParameters,
			Map<String, String> parameters) {
		Map<String, String> changedParameters = new HashMap<String, String>(parameters.size());
		for (Entry<String, String> entry : parameters.entrySet()) {
			String param = entry.getKey();
			String newValue = entry.getValue();
			String oldValue = oldPlacemarkParameters.get(param);
			if ((oldValue == null && newValue != null && newValue.length() > 0)
					|| (oldValue != null && !oldValue.equals(newValue))) {
				changedParameters.put(param, newValue);
			}
		}
		return changedParameters;
	}

	public String[] getKeysInOrder(Map<String, String> receivedBalloonParamaters) {

		List<AttributeDefinition> keyAttributeDefinitions = getRootEntityDefinition().getKeyAttributeDefinitions();
		String[] keys = new String[keyAttributeDefinitions.size()];

		BalloonInputFieldsUtils balloonInputFieldsUtils = new BalloonInputFieldsUtils();
		int i = 0;
		for (AttributeDefinition keyAttribute : keyAttributeDefinitions) {
			String balloonName = balloonInputFieldsUtils.getCollectBalloonParamName(keyAttribute);
			if (!receivedBalloonParamaters.containsKey(balloonName)) {
				throw new IllegalArgumentException(
						"The parameters received do not contain the mandatory parameter " + balloonName);
			}
			keys[i++] = receivedBalloonParamaters.get(balloonName);
		}

		return keys;
	}
	
	private boolean isPreviewRecordID(String[] keyAttributeValues) {
		if (keyAttributeValues.length >= 1) {
			String firstKeyAttributeValue = keyAttributeValues[0];
			return PREVIEW_PLACEMARK_ID_PLACEHOLDER.equals(firstKeyAttributeValue) || 
					PREVIEW_PLACEMARK_ID.equals(firstKeyAttributeValue);
		} else {
			return false;
		}
	}
	
	public void clearRecordCache() {
		recordCache.clear();
	}
	
	public void clearRecordCache(CollectSurvey survey) {
		Iterator<java.util.Map.Entry<RecordKey, CollectRecord>> it = recordCache.entrySet().iterator();
		while (it.hasNext()) {
			Entry<RecordKey, CollectRecord> entry = (Map.Entry<RecordKey, CollectRecord>) it.next();
			RecordKey key = entry.getKey();
			if (key.survey.equals(survey)) {
				it.remove();
			}
		}
	}
	
	private class RecordCache extends LinkedHashMap<RecordKey, CollectRecord> {
		
		private static final long serialVersionUID = 1L;
		private static final int MAX_SIZE = 1000;

		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<RecordKey, CollectRecord> eldest) {
			return size() > MAX_SIZE;
		}
	}
	
	private class RecordKey {
		
		private CollectSurvey survey;
		private String[] keys;
		
		public RecordKey(CollectSurvey survey, String[] keys) {
			this.survey = survey;
			this.keys = keys;
		}
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + Arrays.hashCode(keys);
			result = prime * result + ((survey == null) ? 0 : survey.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			RecordKey other = (RecordKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (!Arrays.equals(keys, other.keys))
				return false;
			if (survey == null) {
				if (other.survey != null)
					return false;
			} else if (!survey.equals(other.survey))
				return false;
			return true;
		}
		private AbstractEarthSurveyService getOuterType() {
			return AbstractEarthSurveyService.this;
		}
		
	}

}
