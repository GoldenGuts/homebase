import WidgetKit
import SwiftUI

@main
struct HomebaseWidgetBundle: WidgetBundle {
    var body: some Widget {
        HomeWidgets().body
        MoreWidgets().body
        if #available(iOS 18.0, *) { EntityToggleControl() }
    }
}

/// Bundles hold at most ten widgets each, so they are split in two.
struct HomeWidgets: WidgetBundle {
    var body: some Widget {
        DashboardWidget()
        FavoritesWidget()
        SuggestedWidget()
        RoomWidget()
        EnergyWidget()
        SecurityWidget()
    }
}

struct MoreWidgets: WidgetBundle {
    var body: some Widget {
        WeatherWidget()
        NowPlayingWidget()
        GraphWidget()
        TileWidget()
    }
}
