package lighthouse.controls;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringExpression;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.*;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import lighthouse.LighthouseBackend;
import lighthouse.Main;
import lighthouse.protocol.Ex;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.LHUtils;
import lighthouse.protocol.Project;
import lighthouse.subwindows.*;
import lighthouse.threading.AffinityExecutor;
import lighthouse.utils.ConcatenatingList;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.MappedList;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static javafx.beans.binding.Bindings.*;
import static javafx.collections.FXCollections.singletonObservableList;
import static lighthouse.utils.GuiUtils.getResource;
import static lighthouse.utils.GuiUtils.informationalAlert;
import static lighthouse.utils.MoreBindings.bindSetToList;
import static lighthouse.utils.MoreBindings.mergeSets;

/**
 * The main content area that shows project details, pledges, a pie chart, buttons etc.
 */
public class ProjectView extends HBox {
    private static final Logger log = LoggerFactory.getLogger(ProjectView.class);

    private static final String BLOCK_EXPLORER_SITE = "https://www.biteasy.com/blockchain/transactions/%s";
    private static final String BLOCK_EXPLORER_SITE_TESTNET = "https://www.biteasy.com/testnet/transactions/%s";

    @FXML Label projectTitle;
    @FXML Label goalAmountLabel;
    @FXML Label raisedAmountLabel;
    @FXML TextFlow description;
    @FXML Label noPledgesLabel;
    @FXML ListView<LHProtos.Pledge> pledgesList;
    @FXML PieChart pieChart;
    @FXML Button actionButton;
    @FXML Pane coverImage;
    @FXML Label numPledgersLabel;
    @FXML Label percentFundedLabel;
    @FXML Button editButton;
    @FXML VBox pledgesListVBox;

    public final ObjectProperty<Project> project = new SimpleObjectProperty<>();
    public final ObjectProperty<EventHandler<ActionEvent>> onBackClickedProperty = new SimpleObjectProperty<>();

    private PieChart.Data emptySlice;
    private final KeyCombination backKey = KeyCombination.valueOf("Shortcut+LEFT");
    private ObservableSet<LHProtos.Pledge> pledges;
    private UIBindings bindings;
    private LongProperty pledgedValue;
    private ObjectBinding<LighthouseBackend.CheckStatus> checkStatus;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;  // project id -> status
    @Nullable private NotificationBarPane.Item notifyBarItem;

    @Nullable private Sha256Hash myPledgeHash;

    private String goalAmountFormatStr;
    private BooleanBinding isFullyFundedAndNotParticipating;

    private enum Mode {
        OPEN_FOR_PLEDGES,
        PLEDGED,
        CAN_CLAIM,
        CLAIMED,
    }
    private SimpleObjectProperty<Mode> mode = new SimpleObjectProperty<>(Mode.OPEN_FOR_PLEDGES);
    private Mode priorMode;

    public ProjectView() {
        // Don't try and access Main.backend here in case you race with startup.
        setupFXML();
        pledgesList.setCellFactory(pledgeListView -> new PledgeListCell());
        project.addListener(x -> updateForProject());
    }

    // Holds together various bindings so we can disconnect them when we switch projects.
    private class UIBindings {
        private final ObservableList<LHProtos.Pledge> sortedByTime;
        private final ConcatenatingList<PieChart.Data> slices;

        public UIBindings() {
            // Bind the project pledges from the backend to the UI components so they react appropriately.
            projectStates = Main.backend.mirrorProjectStates(AffinityExecutor.UI_THREAD);
            projectStates.addListener((javafx.beans.InvalidationListener) x -> {
                setModeFor(project.get(), pledgedValue.get());
            });

            //pledges = fakePledges();
            ObservableSet<LHProtos.Pledge> openPledges = Main.backend.mirrorOpenPledges(project.get(), AffinityExecutor.UI_THREAD);
            ObservableSet<LHProtos.Pledge> claimedPledges = Main.backend.mirrorClaimedPledges(project.get(), AffinityExecutor.UI_THREAD);
            pledges = mergeSets(openPledges, claimedPledges);
            pledges.addListener((SetChangeListener<? super LHProtos.Pledge>) change -> {
                if (change.wasAdded())
                    checkForMyPledge(project.get());
            });

            final long goalAmount = project.get().getGoalAmount().value;

            //    - Bind the amount pledged to the label.
            pledgedValue = LighthouseBackend.bindTotalPledgedProperty(pledges);
            raisedAmountLabel.textProperty().bind(createStringBinding(() -> Coin.valueOf(pledgedValue.get()).toPlainString(), pledgedValue));

            numPledgersLabel.textProperty().bind(Bindings.size(pledges).asString());
            StringExpression format = Bindings.format("%.0f%%", pledgedValue.divide(1.0 * goalAmount).multiply(100.0));
            percentFundedLabel.textProperty().bind(format);

            //    - Make the action button update when the amount pledged changes.
            isFullyFundedAndNotParticipating =
                    pledgedValue.isEqualTo(project.get().getGoalAmount().longValue()).and(
                            mode.isEqualTo(Mode.OPEN_FOR_PLEDGES)
                    );
            pledgedValue.addListener(o -> pledgedValueChanged(goalAmount, pledgedValue));
            pledgedValueChanged(goalAmount, pledgedValue);
            actionButton.disableProperty().bind(isFullyFundedAndNotParticipating);

            //    - Put pledges into the list view.
            ObservableList<LHProtos.Pledge> list1 = FXCollections.observableArrayList();
            bindSetToList(pledges, list1);
            sortedByTime = new SortedList<>(list1, (o1, o2) -> -Long.compareUnsigned(o1.getTimestamp(), o2.getTimestamp()));
            bindContent(pledgesList.getItems(), sortedByTime);

            //    - Convert pledges into pie slices.
            MappedList<PieChart.Data, LHProtos.Pledge> pledgeSlices = new MappedList<>(sortedByTime,
                    pledge -> new PieChart.Data("", pledge.getTotalInputValue()));

            //    - Stick an invisible padding slice on the end so we can see through the unpledged part.
            slices = new ConcatenatingList<>(pledgeSlices, singletonObservableList(emptySlice));

            //    - Connect to the chart widget.
            bindContent(pieChart.getData(), slices);
        }

        public void unbind() {
            numPledgersLabel.textProperty().unbind();
            percentFundedLabel.textProperty().unbind();
            unbindContent(pledgesList.getItems(), sortedByTime);
            unbindContent(pieChart.getData(), slices);
        }
    }

    public void updateForVisibility(boolean visible, @Nullable ObservableMap<Project, LighthouseBackend.CheckStatus> statusMap) {
        if (project.get() == null) return;
        if (visible) {
            // Put the back keyboard shortcut in later, because removing an accelerator whilst a callback is being
            // processed causes a ConcurrentModificationException inside the framework before 8u20.
            Platform.runLater(() -> Main.instance.scene.getAccelerators().put(backKey, () -> backClicked(null)));
            // Make the info bar appear if there's an error
            checkStatus = valueAt(statusMap, project);
            checkStatus.addListener(o -> updateInfoBar());
            // Don't let the user perform an action whilst loading or if there's an error.
            actionButton.disableProperty().unbind();
            actionButton.disableProperty().bind(isFullyFundedAndNotParticipating.or(checkStatus.isNotNull()));
            updateInfoBar();
        } else {
            // Take the back keyboard shortcut out later, because removing an accelerator whilst its callback is being
            // processed causes a ConcurrentModificationException inside the framework before 8u20.
            Platform.runLater(() -> Main.instance.scene.getAccelerators().remove(backKey));
            if (notifyBarItem != null) {
                notifyBarItem.cancel();
                notifyBarItem = null;
            }
        }
    }

    private void updateForProject() {
        pieChart.getData().clear();
        pledgesList.getItems().clear();

        final Project p = project.get();

        projectTitle.setText(p.getTitle());
        goalAmountLabel.setText(String.format(goalAmountFormatStr, p.getGoalAmount().toPlainString()));

        description.getChildren().setAll(new Text(project.get().getMemo()));

        pledgesListVBox.visibleProperty().bind(not(isEmpty(pledgesList.getItems())));
        noPledgesLabel.visibleProperty().bind(isEmpty(pledgesList.getItems()));

        // Load and set up the cover image.
        Image img = new Image(p.getCoverImage().newInput());
        if (img.getException() != null)
            Throwables.propagate(img.getException());
        BackgroundSize cover = new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true);
        BackgroundImage bimg = new BackgroundImage(img, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.DEFAULT, cover);
        coverImage.setBackground(new Background(bimg));

        // Configure the pie chart.
        emptySlice = new PieChart.Data("", 0);

        if (bindings != null)
            bindings.unbind();
        bindings = new UIBindings();

        // This must be done after the binding because otherwise it has no node in the scene graph yet.
        emptySlice.getNode().setOpacity(0.1);
        emptySlice.getNode().setVisible(true);

        checkForMyPledge(p);

        editButton.setVisible(Main.wallet.isProjectMine(p));

        // If a cloned wallet double spends our pledge, the backend can notice this before the wallet does.
        // Because the decision on what the button action should be depends on whether the wallet thinks it's pledged,
        // we have to watch out for this and update the mode here.
        Main.wallet.addOnRevokeHandler(pledge -> setModeFor(p, pledgedValue.get()), Platform::runLater);

        if (p.getPaymentURL() != null) {
            Platform.runLater(() -> {
                Main.instance.scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+R"), () -> Main.backend.refreshProjectStatusFromServer(p));
            });
        }
    }

    private void checkForMyPledge(Project p) {
        LHProtos.Pledge myPledge = Main.wallet.getPledgeFor(p);
        if (myPledge != null)
            myPledgeHash = LHUtils.hashFromPledge(myPledge);
    }

    private void updateInfoBar() {
        if (notifyBarItem != null)
            notifyBarItem.cancel();
        final LighthouseBackend.CheckStatus status = checkStatus.get();
        if (status != null && status.error != null) {
            String msg = status.error.getLocalizedMessage();
            if (status.error instanceof FileNotFoundException)
                msg = "Server error: 404 Not Found: project is not known";
            else if (status.error instanceof Ex.InconsistentUTXOAnswers)
                msg = "Bitcoin P2P network returned inconsistent answers, please contact support";
            else //noinspection ConstantConditions
                if (msg == null)
                    msg = "Internal error: " + status.error.getClass().getName();
            else
                msg = "Error: " + msg;
            notifyBarItem = Main.instance.notificationBar.displayNewItem(msg);
        }
    }

    private void pledgedValueChanged(long goalAmount, LongProperty pledgedValue) {
        // Take the max so if we end up with more pledges than the goal in serverless mode, the pie chart is always
        // full and doesn't go backwards due to a negative pie slice.
        emptySlice.setPieValue(Math.max(0, goalAmount - pledgedValue.get()));
        setModeFor(project.get(), pledgedValue.get());
    }

    private void updateGUIForState() {
        coverImage.setEffect(null);
        switch (mode.get()) {
            case OPEN_FOR_PLEDGES:
                if (isFullyFundedAndNotParticipating.get()) {
                    actionButton.setText("Fully funded");
                    // Disable state is handled by binding.
                } else {
                    actionButton.setText("Pledge");
                }
                break;
            case PLEDGED:
                actionButton.setText("Revoke");
                break;
            case CAN_CLAIM:
                actionButton.setText("Claim");
                break;
            case CLAIMED:
                actionButton.setText("View claim transaction");
                ColorAdjust effect = new ColorAdjust();
                coverImage.setEffect(effect);
                if (priorMode != Mode.CLAIMED) {
                    Timeline timeline = new Timeline(new KeyFrame(GuiUtils.UI_ANIMATION_TIME.multiply(3), new KeyValue(effect.saturationProperty(), -0.9)));
                    timeline.play();
                } else {
                    effect.setSaturation(-0.9);
                }
                break;
        }
    }

    private void setModeFor(Project project, long value) {
        priorMode = mode.get();
        Mode newMode = Mode.OPEN_FOR_PLEDGES;
        if (projectStates.get(project.getID()).state == LighthouseBackend.ProjectState.CLAIMED) {
            newMode = Mode.CLAIMED;
        } else {
            if (Main.wallet.getPledgedAmountFor(project) > 0)
                newMode = Mode.PLEDGED;
            if (value >= project.getGoalAmount().value && Main.wallet.isProjectMine(project))
                newMode = Mode.CAN_CLAIM;
        }
        log.info("Mode is {}", newMode);
        mode.set(newMode);
        if (priorMode == null) priorMode = newMode;
        updateGUIForState();
    }

    private ObservableSet<LHProtos.Pledge> fakePledges() {
        ImmutableList.Builder<LHProtos.Pledge> list = ImmutableList.builder();
        LHProtos.Pledge.Builder builder = LHProtos.Pledge.newBuilder();
        builder.setProjectId("abc");

        long now = Instant.now().getEpochSecond();

        for (int i = 0; i < 1; i++) {
            builder.setTotalInputValue(Coin.CENT.value * 70);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 20);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 10);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 30);
            builder.setTimestamp(now++);
            list.add(builder.build());
        }
        return FXCollections.observableSet(new HashSet<>(list.build()));
    }

    private void setupFXML() {
        try {
            FXMLLoader loader = new FXMLLoader(getResource("controls/project_view.fxml"));
            loader.setRoot(this);
            loader.setController(this);
            // The following line is supposed to help Scene Builder, although it doesn't seem to be needed for me.
            loader.setClassLoader(getClass().getClassLoader());
            loader.load();

            goalAmountFormatStr = goalAmountLabel.getText();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private void backClicked(@Nullable ActionEvent event) {
        if (onBackClickedProperty.get() != null)
            onBackClickedProperty.get().handle(event);
    }

    @FXML
    private void actionClicked(ActionEvent event) {
        final Project p = project.get();
        switch (mode.get()) {
            case OPEN_FOR_PLEDGES:
                if (Main.wallet.getBalance().value == 0)
                    Main.instance.mainWindow.tellUserToSendSomeMoney();
                else
                    makePledge(p);
                break;
            case PLEDGED:
                revokePledge(p);
                break;
            case CAN_CLAIM:
                claimPledges(p);
                break;
            case CLAIMED:
                viewClaim(p);
                break;
            default:
                throw new AssertionError();  // Unreachable.
        }
    }

    private void viewClaim(Project p) {
        LighthouseBackend.ProjectStateInfo info = projectStates.get(p.getID());
        checkState(info.state == LighthouseBackend.ProjectState.CLAIMED);
        String url = String.format(Main.params == TestNet3Params.get() ? BLOCK_EXPLORER_SITE_TESTNET : BLOCK_EXPLORER_SITE, info.claimedBy);
        log.info("Opening {}", url);
        Main.instance.getHostServices().showDocument(url);
    }

    private void makePledge(Project p) {
        log.info("Invoking pledge screen");
        PledgeWindow window = Main.instance.<PledgeWindow>overlayUI("subwindows/pledge.fxml", "Pledge").controller;
        window.project = p;
        window.setLimits(p.getGoalAmount().subtract(Coin.valueOf(pledgedValue.get())), p.getMinPledgeAmount());
        window.onSuccess = () -> {
            mode.set(Mode.PLEDGED);
            updateGUIForState();
        };
    }

    private void claimPledges(Project p) {
        log.info("Claim button clicked for {}", p);
        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForClaim(p, pledges);
        overlay.controller.onSuccess = () -> {
            mode.set(Mode.OPEN_FOR_PLEDGES);
            updateGUIForState();
        };
    }

    private void revokePledge(Project project) {
        log.info("Revoke button clicked: {}", project.getTitle());
        LHProtos.Pledge pledge = Main.wallet.getPledgeFor(project);
        checkNotNull(pledge, "UI invariant violation");   // Otherwise our UI is really messed up.

        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForRevoke(pledge);
        overlay.controller.onSuccess = () -> {
            mode.set(Mode.OPEN_FOR_PLEDGES);
            updateGUIForState();
        };
    }

    public void setProject(Project project) {
        this.project.set(project);
    }

    public Project getProject() {
        return this.project.get();
    }

    // TODO: Should we show revoked pledges crossed out?
    private class PledgeListCell extends ListCell<LHProtos.Pledge> {
        private Label status, email, memoSnippet, date;
        private Label viewMore;

        public PledgeListCell() {
            Pane pane;
            HBox hbox;
            VBox vbox = new VBox(
                    (status = new Label()),
                    (hbox = new HBox(
                            (email = new Label()),
                            (pane = new Pane()),
                            (date = new Label())
                    )),
                    (memoSnippet = new Label()),
                    (viewMore = new Label("View more"))
            );
            vbox.getStyleClass().add("pledge-cell");
            status.getStyleClass().add("pledge-cell-status");
            email.getStyleClass().add("pledge-cell-email");
            HBox.setHgrow(pane, Priority.ALWAYS);
            vbox.setFillWidth(true);
            hbox.maxWidthProperty().bind(vbox.widthProperty());
            date.getStyleClass().add("pledge-cell-date");
            date.setMinWidth(USE_PREF_SIZE);    // Date is shown in preference to contact if contact data is too long
            memoSnippet.getStyleClass().add("pledge-cell-memo");
            memoSnippet.setWrapText(true);
            memoSnippet.maxWidthProperty().bind(vbox.widthProperty());
            memoSnippet.setMaxHeight(100);
            viewMore.getStyleClass().add("hover-link");
            viewMore.setOnMouseClicked(ev -> ShowPledgeWindow.open(project.get(), getItem()));
            viewMore.setAlignment(Pos.CENTER_RIGHT);
            viewMore.prefWidthProperty().bind(vbox.widthProperty());
            vbox.setPrefHeight(0);
            vbox.setMaxHeight(USE_PREF_SIZE);
            setGraphic(vbox);
            setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2)
                    ShowPledgeWindow.open(project.get(), getItem());
            });
        }

        @Override
        protected void updateItem(LHProtos.Pledge pledge, boolean empty) {
            super.updateItem(pledge, empty);
            if (empty) {
                getGraphic().setVisible(false);
                return;
            }
            getGraphic().setVisible(true);
            String msg = Coin.valueOf(pledge.getTotalInputValue()).toFriendlyString();
            if (LHUtils.hashFromPledge(pledge).equals(myPledgeHash))
                msg += " (yours)";
            status.setText(msg);
            email.setText(pledge.getPledgeDetails().getContactAddress());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime time = LocalDateTime.ofEpochSecond(pledge.getTimestamp(), 0, ZoneOffset.UTC);
            date.setText(time.format(formatter));
            memoSnippet.setText(pledge.getPledgeDetails().getMemo());
        }
    }

    @FXML
    public void edit(ActionEvent event) {
        log.info("Edit button clicked");
        if (pledgedValue.get() > 0) {
            informationalAlert("Unable to edit",
                    "You cannot edit a project that has already started gathering pledges, as otherwise existing " +
                            "pledges could be invalidated and participants could get confused. If you would like to " +
                            "change this project either create a new one, or request revocation of existing pledges."
            );
            return;
        }
        EditProjectWindow.openForEdit(project.get());
    }

    @FXML
    public void onViewTechDetailsClicked(MouseEvent event) {
        log.info("View tech details of project clicked for {}", project.get().getTitle());
        ProjectTechDetailsWindow.open(project.get());
    }

    @FXML
    public void exportPledgesClicked(MouseEvent event) {
        log.info("Export pledges clicked for {}", project.get().getTitle());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export pledges to CSV file");
        chooser.setInitialFileName("pledges.csv");
        GuiUtils.platformFiddleChooser(chooser);
        File file = chooser.showSaveDialog(Main.instance.mainStage);
        if (file == null) {
            log.info(" ... but user cancelled");
            return;
        }
        log.info("Saving pledges as CSV to file {}", file);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()), Charsets.UTF_8)) {
            writer.append(String.format("num_satoshis,time,email,message%n"));
            for (LHProtos.Pledge pledge : pledgesList.getItems()) {
                String time = Instant.ofEpochSecond(pledge.getTimestamp()).atZone(ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME).replace(",", "");
                String memo = pledge.getPledgeDetails().getMemo().replace('\n', ' ').replace(",", "");
                writer.append(String.format("%d,%s,%s,%s%n", pledge.getTotalInputValue(), time, pledge.getPledgeDetails().getContactAddress(), memo));
            }
            GuiUtils.informationalAlert("Export succeeded", "Pledges are stored in a CSV file, which can be loaded with any spreadsheet application. Amounts are specified in satoshis.");
        } catch (IOException e) {
            log.error("Failed to write to csv file", e);
            GuiUtils.informationalAlert("Export failed", "Lighthouse was unable to save pledge data to the selected file: %s", e.getLocalizedMessage());
        }
    }
}
