package com.mapbox.navigation.ui;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.mapbox.android.core.location.LocationEngine;
import com.mapbox.api.directions.v5.models.BannerInstructions;
import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.RouteOptions;
import com.mapbox.api.directions.v5.models.VoiceInstructions;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.utils.PolylineUtils;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.offline.OfflineManager;
import com.mapbox.navigation.base.internal.extensions.ContextEx;
import com.mapbox.navigation.base.formatter.DistanceFormatter;
import com.mapbox.navigation.base.options.OnboardRouterOptions;
import com.mapbox.navigation.base.options.NavigationOptions;
import com.mapbox.navigation.base.trip.model.RouteProgress;
import com.mapbox.navigation.base.TimeFormat;
import com.mapbox.navigation.core.internal.MapboxDistanceFormatter;
import com.mapbox.navigation.core.MapboxNavigation;
import com.mapbox.navigation.core.directions.session.RoutesObserver;
import com.mapbox.navigation.core.replay.MapboxReplayer;
import com.mapbox.navigation.core.replay.ReplayLocationEngine;
import com.mapbox.navigation.core.replay.history.ReplayEventBase;
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver;
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper;
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver;
import com.mapbox.navigation.core.trip.session.OffRouteObserver;
import com.mapbox.navigation.core.trip.session.TripSessionState;
import com.mapbox.navigation.core.trip.session.TripSessionStateObserver;
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver;
import com.mapbox.navigation.ui.camera.Camera;
import com.mapbox.navigation.ui.camera.DynamicCamera;
import com.mapbox.navigation.ui.feedback.FeedbackItem;
import com.mapbox.navigation.ui.internal.instruction.BannerInstructionModel;
import com.mapbox.navigation.ui.internal.instruction.InstructionModel;
import com.mapbox.navigation.ui.internal.ConnectivityStatusProvider;
import com.mapbox.navigation.ui.internal.junction.RouteJunctionModel;
import com.mapbox.navigation.ui.summary.SummaryModel;
import com.mapbox.navigation.ui.voice.NavigationSpeechPlayer;
import com.mapbox.navigation.ui.voice.SpeechPlayer;
import com.mapbox.navigation.ui.voice.SpeechPlayerProvider;
import com.mapbox.navigation.ui.voice.VoiceInstructionLoader;
import okhttp3.Cache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.mapbox.navigation.base.internal.extensions.LocaleEx.getLocaleDirectionsRoute;
import static com.mapbox.navigation.base.internal.extensions.LocaleEx.getUnitTypeForLocale;
import static com.mapbox.navigation.core.telemetry.events.FeedbackEvent.UI;

public class NavigationViewModel extends AndroidViewModel {

  private static final String OKHTTP_INSTRUCTION_CACHE = "okhttp-instruction-cache";
  private static final long TEN_MEGABYTE_CACHE_SIZE = 10 * 1024 * 1024;

  private final MutableLiveData<InstructionModel> instructionModel = new MutableLiveData<>();
  private final MutableLiveData<BannerInstructionModel> bannerInstructionModel = new MutableLiveData<>();
  private final MutableLiveData<SummaryModel> summaryModel = new MutableLiveData<>();
  private final MutableLiveData<Boolean> isOffRoute = new MutableLiveData<>();
  private final MutableLiveData<RouteJunctionModel> routeJunctionModel = new MutableLiveData<>();
  private final MutableLiveData<Location> navigationLocation = new MutableLiveData<>();
  private final MutableLiveData<DirectionsRoute> route = new MutableLiveData<>();
  private final MutableLiveData<Boolean> shouldRecordScreenshot = new MutableLiveData<>();
  private final MutableLiveData<Boolean> isFeedbackSentSuccess = new MutableLiveData<>();
  private final MutableLiveData<Point> destination = new MutableLiveData<>();

  private MapboxNavigation navigation;
  private NavigationViewEventDispatcher navigationViewEventDispatcher;
  private SpeechPlayer speechPlayer;
  private VoiceInstructionLoader voiceInstructionLoader;
  private VoiceInstructionCache voiceInstructionCache;
  private int voiceInstructionsToAnnounce = 0;
  private RouteProgress routeProgress;
  private FeedbackItem feedbackItem;
  private String language;
  private DistanceFormatter distanceFormatter;
  private String accessToken;
  @TimeFormat.Type
  private int timeFormatType;
  private boolean isRunning;
  private MapConnectivityController connectivityController;
  private MapOfflineManager mapOfflineManager;
  private NavigationViewModelProgressObserver navigationProgressObserver =
      new NavigationViewModelProgressObserver(this);

  private NavigationViewOptions navigationViewOptions;
  private MapboxReplayer mapboxReplayer = new MapboxReplayer();

  public NavigationViewModel(Application application) {
    super(application);
    this.accessToken = Mapbox.getAccessToken();
    this.connectivityController = new MapConnectivityController();
  }

  @TestOnly NavigationViewModel(Application application, MapboxNavigation navigation,
      MapConnectivityController connectivityController, MapOfflineManager mapOfflineManager,
      NavigationViewOptions navigationViewOptions) {
    super(application);
    this.navigation = navigation;
    this.connectivityController = connectivityController;
    this.mapOfflineManager = mapOfflineManager;
    this.navigationViewOptions = navigationViewOptions;
  }

  @TestOnly NavigationViewModel(Application application, MapboxNavigation navigation,
      NavigationViewEventDispatcher dispatcher, VoiceInstructionCache cache, SpeechPlayer speechPlayer) {
    super(application);
    this.navigation = navigation;
    this.navigationViewEventDispatcher = dispatcher;
    this.voiceInstructionCache = cache;
    this.speechPlayer = speechPlayer;
  }

  public void onDestroy(boolean isChangingConfigurations) {
    if (!isChangingConfigurations) {
      endNavigation();
      destroyMapOffline();
      deactivateInstructionPlayer();
      isRunning = false;
    }
    clearDynamicCameraMap();
    navigationViewEventDispatcher = null;
  }

  public void setMuted(boolean isMuted) {
    speechPlayer.setMuted(isMuted);
  }

  /**
   * Used to update an existing {@link FeedbackItem}
   * with a feedback type and description.
   * <p>
   * Uses cached feedbackId to ensure the proper item is updated.
   *
   * @param feedbackItem item to be updated
   */
  public void updateFeedback(FeedbackItem feedbackItem) {
    this.feedbackItem = feedbackItem;
    isFeedbackSentSuccess.setValue(false);
    shouldRecordScreenshot.setValue(true);
  }

  /**
   * Returns the current instance of {@link MapboxNavigation}.
   * <p>
   * Will be null if navigation has not been initialized.
   */
  @Nullable
  public MapboxNavigation retrieveNavigation() {
    return navigation;
  }

  @Override
  protected void onCleared() {
    mapboxReplayer.finish();
    super.onCleared();
  }

  void initializeEventDispatcher(NavigationViewEventDispatcher navigationViewEventDispatcher) {
    this.navigationViewEventDispatcher = navigationViewEventDispatcher;
  }

  /**
   * This method will pass {@link NavigationOptions} from the {@link NavigationViewOptions}
   * to this view model to be used to initialize {@link MapboxNavigation}.
   *
   * @param options to init MapboxNavigation
   */
  @SuppressLint("MissingPermission")
  void initialize(NavigationViewOptions options) {
    this.navigationViewOptions = options;

    initializeLanguage(options);
    NavigationOptions.Builder updatedOptionsBuilder = options.navigationOptions()
        .toBuilder()
        .accessToken(accessToken)
        .isFromNavigationUi(true);

    if (options.navigationOptions().getDistanceFormatter() == null) {
      this.distanceFormatter = buildDistanceFormatter(options);
      updatedOptionsBuilder.distanceFormatter(distanceFormatter);
    } else {
      this.distanceFormatter = options.navigationOptions().getDistanceFormatter();
    }

    if (options.navigationOptions().getOnboardRouterOptions() == null) {
      OnboardRouterOptions onboardRouterOptions =
          MapboxNavigation.defaultNavigationOptions(getApplication(), accessToken).getOnboardRouterOptions();
      updatedOptionsBuilder.onboardRouterOptions(onboardRouterOptions);
    }

    NavigationOptions updatedOptions = updatedOptionsBuilder.build();
    initializeTimeFormat(updatedOptions);
    if (!isRunning()) {
      LocationEngine locationEngine = initializeLocationEngineFrom(options);
      initializeNavigation(getApplication(), updatedOptions, locationEngine);
      initializeVoiceInstructionLoader();
      initializeVoiceInstructionCache();
      initializeNavigationSpeechPlayer(options);
      initializeMapOfflineManager(options);
    }
    navigation.setRoutes(Arrays.asList(options.directionsRoute()));
    navigation.startTripSession();
    voiceInstructionCache.initCache(options.directionsRoute());
  }

  void updateFeedbackScreenshot(String screenshot) {
    if (feedbackItem != null) {
      MapboxNavigation.postUserFeedback(feedbackItem.getFeedbackType(),
          feedbackItem.getDescription(), UI, screenshot,
          feedbackItem.getFeedbackSubType().toArray(new String[0]));
      sendEventFeedback(feedbackItem);
      isFeedbackSentSuccess.setValue(true);
      feedbackItem = null;
    }

    shouldRecordScreenshot.setValue(false);
  }

  boolean isRunning() {
    return isRunning;
  }

  boolean isMuted() {
    if (speechPlayer == null) {
      return false;
    }
    return speechPlayer.isMuted();
  }

  void stopNavigation() {
    navigation.unregisterRouteProgressObserver(navigationProgressObserver);
    navigation.unregisterLocationObserver(navigationProgressObserver);
    navigation.unregisterRoutesObserver(routesObserver);
    navigation.unregisterOffRouteObserver(offRouteObserver);
    navigation.unregisterBannerInstructionsObserver(bannerInstructionsObserver);
    navigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver);
    navigation.unregisterTripSessionStateObserver(tripSessionStateObserver);
    navigation.stopTripSession();
  }

  void updateRouteProgress(RouteProgress routeProgress) {
    this.routeProgress = routeProgress;

    instructionModel.setValue(new InstructionModel(distanceFormatter, routeProgress));
    summaryModel.setValue(new SummaryModel(getApplication(), distanceFormatter, routeProgress, timeFormatType));
    routeJunctionModel.setValue(new RouteJunctionModel(routeProgress));
  }

  void updateLocation(Location location) {
    navigationLocation.setValue(location);
  }

  private void updateBannerInstruction(BannerInstructions bannerInstructions) {
    BannerInstructions instructions = retrieveInstructionsFromBannerEvent(bannerInstructions);
    if (instructions != null) {
      BannerInstructionModel model = new BannerInstructionModel(distanceFormatter, routeProgress, instructions);
      bannerInstructionModel.setValue(model);
    }
  }

  LiveData<Location> retrieveNavigationLocation() {
    return navigationLocation;
  }

  LiveData<DirectionsRoute> retrieveRoute() {
    return route;
  }

  LiveData<Point> retrieveDestination() {
    return destination;
  }

  LiveData<Boolean> retrieveShouldRecordScreenshot() {
    return shouldRecordScreenshot;
  }

  LiveData<Boolean> retrieveIsFeedbackSentSuccess() {
    return isFeedbackSentSuccess;
  }

  public LiveData<InstructionModel> retrieveInstructionModel() {
    return instructionModel;
  }

  public LiveData<RouteJunctionModel> retrieveRouteJunctionModelUpdates() {
    return routeJunctionModel;
  }

  public LiveData<BannerInstructionModel> retrieveBannerInstructionModel() {
    return bannerInstructionModel;
  }

  public LiveData<Boolean> retrieveIsOffRoute() {
    return isOffRoute;
  }

  public LiveData<SummaryModel> retrieveSummaryModel() {
    return summaryModel;
  }

  protected NavigationViewOptions getNavigationViewOptions() {
    return navigationViewOptions;
  }

  private void initializeLanguage(NavigationUiOptions options) {
    RouteOptions routeOptions = options.directionsRoute().routeOptions();
    language = ContextEx.inferDeviceLanguage(getApplication());
    if (routeOptions != null) {
      language = routeOptions.language();
    }
  }

  private String initializeUnitType(NavigationUiOptions options) {
    RouteOptions routeOptions = options.directionsRoute().routeOptions();
    String unitType = getUnitTypeForLocale(ContextEx.inferDeviceLocale(getApplication()));
    if (routeOptions != null) {
      unitType = routeOptions.voiceUnits();
    }
    return unitType;
  }

  private void initializeTimeFormat(NavigationOptions options) {
    timeFormatType = options.getTimeFormatType();
  }

  private DistanceFormatter buildDistanceFormatter(final NavigationViewOptions options) {
    final String unitType = initializeUnitType(options);
    final int roundingIncrement = options.roundingIncrement();
    final Locale locale = getLocaleDirectionsRoute(options.directionsRoute(), getApplication());
    return new MapboxDistanceFormatter.Builder()
        .withUnitType(unitType)
        .withRoundingIncrement(roundingIncrement)
        .withLocale(locale)
        .build(getApplication());
  }

  private void initializeNavigationSpeechPlayer(NavigationViewOptions options) {
    SpeechPlayer speechPlayer = options.speechPlayer();
    if (speechPlayer != null) {
      this.speechPlayer = speechPlayer;
      return;
    }
    boolean isVoiceLanguageSupported = options.directionsRoute().voiceLanguage() != null;
    SpeechPlayerProvider speechPlayerProvider = initializeSpeechPlayerProvider(isVoiceLanguageSupported);
    this.speechPlayer = new NavigationSpeechPlayer(speechPlayerProvider);
  }

  private void initializeMapOfflineManager(NavigationViewOptions options) {
    MapOfflineOptions mapOfflineOptions = options.offlineMapOptions();
    if (mapOfflineOptions == null) {
      return;
    }
    String mapStyleUrl = mapOfflineOptions.getStyleUrl();
    Context applicationContext = getApplication().getApplicationContext();
    OfflineManager offlineManager = OfflineManager.getInstance(applicationContext);
    float pixelRatio = applicationContext.getResources().getDisplayMetrics().density;
    OfflineRegionDefinitionProvider definitionProvider = new OfflineRegionDefinitionProvider(mapStyleUrl, pixelRatio);
    OfflineMetadataProvider metadataProvider = new OfflineMetadataProvider();
    RegionDownloadCallback regionDownloadCallback = new RegionDownloadCallback(connectivityController);
    mapOfflineManager = new MapOfflineManager(offlineManager, definitionProvider, metadataProvider,
        connectivityController, regionDownloadCallback);
    navigation.registerRouteProgressObserver(mapOfflineManager);
  }

  private void initializeVoiceInstructionLoader() {
    Cache cache = new Cache(new File(getApplication().getCacheDir(), OKHTTP_INSTRUCTION_CACHE),
        TEN_MEGABYTE_CACHE_SIZE);
    voiceInstructionLoader = new VoiceInstructionLoader(getApplication(), accessToken, cache);
  }

  private void initializeVoiceInstructionCache() {
    ConnectivityStatusProvider connectivityStatus = new ConnectivityStatusProvider(getApplication());
    voiceInstructionCache = new VoiceInstructionCache(navigation, voiceInstructionLoader, connectivityStatus);
  }

  @NonNull
  private SpeechPlayerProvider initializeSpeechPlayerProvider(boolean voiceLanguageSupported) {
    return new SpeechPlayerProvider(getApplication(), language, voiceLanguageSupported, voiceInstructionLoader);
  }

  @Nullable
  private LocationEngine initializeLocationEngineFrom(final NavigationViewOptions options) {
    if (options.locationEngine() != null) {
      return options.locationEngine();
    } else if (options.shouldSimulateRoute()) {
      ReplayLocationEngine replayLocationEngine = new ReplayLocationEngine(mapboxReplayer);
      final Point lastLocation = getOriginOfRoute(options.directionsRoute());
      ReplayEventBase replayEventOrigin = ReplayRouteMapper.mapToUpdateLocation(0.0, lastLocation);
      mapboxReplayer.pushEvents(Collections.singletonList(replayEventOrigin));
      mapboxReplayer.play();
      return replayLocationEngine;
    } else {
      return null;
    }
  }

  private void initializeNavigation(
          Context context,
          NavigationOptions options,
          @Nullable LocationEngine locationEngine) {
    if (locationEngine == null) {
      navigation = new MapboxNavigation(context, options);
    } else {
      navigation = new MapboxNavigation(context, options, locationEngine);
    }
    addNavigationListeners();
  }

  private void addNavigationListeners() {
    navigation.registerRouteProgressObserver(navigationProgressObserver);
    navigation.registerLocationObserver(navigationProgressObserver);
    navigation.registerRoutesObserver(routesObserver);
    navigation.registerOffRouteObserver(offRouteObserver);
    navigation.registerBannerInstructionsObserver(bannerInstructionsObserver);
    navigation.registerVoiceInstructionsObserver(voiceInstructionsObserver);
    navigation.registerTripSessionStateObserver(tripSessionStateObserver);
    if (navigationViewOptions.shouldSimulateRoute()) {
      navigation.registerRouteProgressObserver(new ReplayProgressObserver(mapboxReplayer));
    }
  }

  private VoiceInstructionsObserver voiceInstructionsObserver = new VoiceInstructionsObserver() {
    @Override
    public void onNewVoiceInstructions(@NotNull VoiceInstructions voiceInstructions) {
      voiceInstructionCache.cache();
      voiceInstructionsToAnnounce++;
      voiceInstructionCache.update(voiceInstructionsToAnnounce);
      speechPlayer.play(retrieveAnnouncementFromSpeechEvent(voiceInstructions));
    }
  };

  private OffRouteObserver offRouteObserver = new OffRouteObserver() {
    @Override
    public void onOffRouteStateChanged(boolean offRoute) {
      if (offRoute) {
        speechPlayer.onOffRoute();
      }
      isOffRoute.setValue(offRoute);
    }
  };

  private BannerInstructionsObserver bannerInstructionsObserver = new BannerInstructionsObserver() {
    @Override
    public void onNewBannerInstructions(@NotNull BannerInstructions bannerInstructions) {
      updateBannerInstruction(bannerInstructions);
    }
  };

  private TripSessionStateObserver tripSessionStateObserver = new TripSessionStateObserver() {
    @Override
    public void onSessionStateChanged(@NotNull TripSessionState tripSessionState) {
      if (tripSessionState == TripSessionState.STOPPED) {
        navigationViewEventDispatcher.onNavigationFinished();
      } else if (tripSessionState == TripSessionState.STARTED) {
        navigationViewEventDispatcher.onNavigationRunning();
        isRunning = true;
      }
    }
  };

  private RoutesObserver routesObserver = new RoutesObserver() {
    @Override
    public void onRoutesChanged(@NotNull List<? extends DirectionsRoute> routes) {
      if (routes.size() > 0) {
        route.setValue(routes.get(0));
      }
    }
  };

  private void endNavigation() {
    if (navigation != null) {
      navigation.onDestroy();
    }
  }

  private void clearDynamicCameraMap() {
    if (navigation != null) {
      Camera cameraEngine = navigationViewOptions.camera();
      boolean isDynamicCamera = cameraEngine instanceof DynamicCamera;
      if (isDynamicCamera) {
        ((DynamicCamera) cameraEngine).clearMap();
      }
    }
  }

  private void destroyMapOffline() {
    if (mapOfflineManager != null) {
      mapOfflineManager.onDestroy();
    }
    connectivityController.assign(null);
  }

  private void deactivateInstructionPlayer() {
    if (speechPlayer != null) {
      speechPlayer.onDestroy();
    }
  }

  private void sendEventFeedback(FeedbackItem feedbackItem) {
    if (navigationViewEventDispatcher != null) {
      navigationViewEventDispatcher.onFeedbackSent(feedbackItem);
    }
  }

  private VoiceInstructions retrieveAnnouncementFromSpeechEvent(VoiceInstructions announcement) {
    if (navigationViewEventDispatcher != null) {
      announcement = navigationViewEventDispatcher.onAnnouncement(announcement);
    }
    return announcement;
  }

  private BannerInstructions retrieveInstructionsFromBannerEvent(BannerInstructions instructions) {
    if (navigationViewEventDispatcher != null) {
      instructions = navigationViewEventDispatcher.onBannerDisplay(instructions);
    }
    return instructions;
  }

  private Point getOriginOfRoute(final DirectionsRoute directionsRoute) {
    return PolylineUtils.decode(directionsRoute.geometry(), 6).get(0);
  }
}